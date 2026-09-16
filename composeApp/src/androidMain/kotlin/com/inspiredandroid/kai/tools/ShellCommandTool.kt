package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.SandboxSessions
import com.inspiredandroid.kai.data.currentConversationIdOrNull
import com.inspiredandroid.kai.linux.LinuxDistro
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SandboxState
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_execute_shell_command_description
import kai.composeapp.generated.resources.tool_execute_shell_command_name
import org.koin.java.KoinJavaComponent.inject

/** Shell-safe environment names: anything else would break the KEY='value' prefix. */
private val ENV_KEY_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

/**
 * Built per distro rather than patched afterwards: the package manager and the
 * distro's name appear in several places, and a model that is told "Alpine" and
 * then handed a Debian rootfs wastes turns on `apk` commands that do not exist.
 */
private fun toolDescription(distro: LinuxDistro): String {
    val install = distro.packageManager.installCommand("<package>")
    return """Execute a shell command in a ${distro.displayName} sandbox and return stdout, stderr, exit code, and current working directory. The environment is a full ${distro.displayName} system running via proot.

Shell session is PERSISTENT across calls within THIS conversation: cwd, exported environment variables, and any in-shell state carry from one call to the next, just like a normal terminal. So "cd /tmp" in one call, then "pwd" in the next, returns "/tmp". You do NOT need to chain "cd dir && command" unless you want directory changes to be one-shot. Other conversations and the in-app Terminal tab each have their own isolated shells; the rootfs and /root are still shared on disk, so files persist across all of them.

Pre-installed: ${distro.basePackages.joinToString(", ")}. Optional bundle (installed from Settings): ${distro.optionalPackages.joinToString(", ")} — that covers remote-server tools ssh, scp, sftp, lftp (FTP/FTPS) and rsync. Use them directly, e.g. "ssh user@host 'remote command'", "sftp user@host", "lftp -c 'open ftp://...; put file'". Authentication state (~/.ssh keys, known_hosts) persists.

For SSH workflows: prefer the ssh_configure_host tool once per remote — it writes ~/.ssh/config so subsequent calls don't have to repeat host/user/port/identity flags. After registering, invoke ssh BY THE ALIAS: `ssh myalias 'cmd'`, `scp file myalias:`, `sftp myalias`. The whole point of the config is to feed the alias; bypassing it with `user@host` discards every setting the tool just wrote.

Note: SSH multiplexing (ControlMaster) is intentionally NOT enabled — Android's kernel-level link() restriction prevents openssh from creating its control socket inside this sandbox. Each ssh call does a full TCP+auth handshake. That is the correct, expected behavior here; do not try to force it back on with -o ControlMaster=auto or by writing your own ControlPath — it will produce a muxserver_listen Permission denied error.

Android boundary — this sandbox is proot-isolated (untrusted_app) and CANNOT reach the host. Do NOT try from here, all will fail by design:
- No `shizuku` binary and no `adb` binary here — never `find / -name shizuku` (it only scans the emulated rootfs and times out). The Shizuku/adb path is the separate `privileged_shell` tool, which runs OUTSIDE this sandbox as the shell user.
- No SMS/contacts via `content query` — those need the dedicated `check_sms`/`read_sms`/`search_sms` tools plus the user's runtime permission (FOSS build for SMS). A "permission denied" from here means use the tool, not retry with sudo/root.
- No other apps' `/data/data` — neither this sandbox nor the shell user can read it (only a rooted Shizuku can). Report the blocker instead of escalating.

Password-only servers (no key auth): this shell can't answer interactive password prompts directly (no PTY, ssh reads from /dev/tty). Heredoc stdin will NOT deliver a password. Install sshpass once with `${distro.packageManager.installCommand("sshpass")}`, then drive the connection as `sshpass -p '<password>' ssh <alias> '<remote-cmd>'` — or `sshpass -f <password-file> ssh <alias>` to keep the password off the command line. sshpass fakes a PTY internally, which is the only path that actually works.

Limits and behavior:
- Output is capped at 15000 characters per stream; for large output, pipe through head/tail.
- Default timeout: 30s, max: 600s (10 min). Long-running interactive commands (e.g. ssh sessions held across messages) work because the shell is persistent — but a SINGLE call still hits the timeout if it doesn't return.
- Fullscreen TUIs (top, htop, vim, less, nano, anything ncurses) WILL NOT WORK — the sandbox has no PTY. Use non-interactive variants: "top -bn1" for a one-shot snapshot, "ps aux" for processes, redirect editor output, etc.
- Set background=true to run a long-lived process detached from the shell (writes to its own session_id). Use manage_process to check on it.
- Set fresh=true to run in a one-shot isolated shell that doesn't share state with the persistent session. Useful when you specifically want isolation; rarely needed.

Install extra packages with: $install

To show a file you produced in /root to the user, call open_file with the path relative to /root (e.g. open_file path="page.html"). File needs to be self-contained."""
}

object ShellCommandTool : Tool {
    private val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)

    private fun isProotReady(): Boolean = sandboxManager.state.value is SandboxState.Ready

    // A getter, not a stored value: the schema is read when tools are advertised,
    // by which point the installed distro is known (and can have changed since).
    // Tier follows readiness: full proot Linux when Ready, host mksh/toybox
    // otherwise — the description always matches the backend that will run.
    override val schema: ToolSchema get() {
        return if (isProotReady()) {
            ToolSchema(
                name = "execute_shell_command",
                description = toolDescription(sandboxManager.distro),
                parameters = shellParameters,
            )
        } else {
            ToolSchema(
                name = "execute_shell_command",
                description = nativeToolDescription(sandboxManager.nativeHome.absolutePath),
                parameters = shellParameters,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun execute(args: Map<String, Any>): Any {
        val command = (args["command"] as? String)?.takeIf { it.isNotBlank() }
            ?: return mapOf("success" to false, "error" to "Command is required")

        val ready = isProotReady()
        if (!ready && !sandboxManager.isNativeShellAvailable()) {
            return mapOf("success" to false, "error" to "No shell available: /system/bin/sh is missing on this device. Install the Linux sandbox in Settings > Tools for the full shell.")
        }
        // Home differs per tier: /root inside the rootfs, app-private storage natively.
        val homeDir = if (ready) "/root" else sandboxManager.nativeHome.absolutePath

        val timeoutSeconds = ((args["timeout"] as? Number)?.toLong() ?: args["timeout"]?.toString()?.toLongOrNull() ?: 30L)
            .coerceIn(1, 600L)
        val workingDir = args["working_dir"] as? String

        val rawEnv = (args["env"] as? Map<String, Any>)
            ?.mapValues { it.value.toString() }
            ?: emptyMap()
        // Only shell-safe names reach the prefix; anything else would emit
        // broken syntax like FOO-BAR=val, so those entries are dropped with a
        // warning instead of failing silently.
        val envMap = rawEnv.filterKeys { it.matches(ENV_KEY_REGEX) }
        val droppedEnvKeys = rawEnv.keys.filterNot { it.matches(ENV_KEY_REGEX) }

        fun withEnvWarning(result: Map<String, Any>): Map<String, Any> = if (droppedEnvKeys.isEmpty()) {
            result
        } else {
            result + mapOf(
                "env_warning" to "Ignored invalid env keys (${droppedEnvKeys.joinToString(", ")}); " +
                    "names must match [A-Za-z_][A-Za-z0-9_]*.",
            )
        }

        val background = (args["background"] as? Boolean) ?: (args["background"]?.toString()?.equals("true", ignoreCase = true) == true)
        if (background) {
            return withEnvWarning(
                ProcessManagerTool.processManager.startBackground(
                    command,
                    timeoutSeconds,
                    workingDir ?: homeDir,
                    envMap,
                ),
            )
        }

        val fresh = (args["fresh"] as? Boolean) ?: (args["fresh"]?.toString()?.equals("true", ignoreCase = true) == true)
        if (fresh) {
            if (!ready) {
                return withEnvWarning(
                    sandboxManager.runNativeOneShot(command, timeoutSeconds, workingDir, envMap),
                )
            }
            val executor = sandboxManager.createProotExecutor()
            return withEnvWarning(executor.execute(command, timeoutSeconds, workingDir ?: homeDir, envMap))
        }

        // Persistent shell path. Each conversation gets its own shell session so
        // state from one chat (cwd, exports, ssh-agent, &-jobs) doesn't leak into
        // another. Tools invoked outside a conversation context fall through to
        // a shared default session.
        val sessionId = currentConversationIdOrNull() ?: SandboxSessions.DEFAULT
        // Apply env as a per-command prefix (FOO=bar BAR=baz user_command) so
        // the env doesn't bleed into the session. cd is intentionally persistent:
        // the LLM is told that's the case in the tool description.
        val prefix = buildString {
            if (workingDir != null) {
                append("cd ").append(shellSingleQuote(workingDir)).append(" && ")
            }
            envMap.forEach { (k, v) ->
                append(k).append('=').append(shellSingleQuote(v)).append(' ')
            }
        }
        val wrapped = if (prefix.isEmpty()) command else "$prefix$command"
        // Pass the unwrapped command as displayCommand so the Terminal UI shows
        // what the agent asked for, not the cd/env scaffolding we add.
        if (!ready) {
            return withEnvWarning(
                sandboxManager.nativeShellFor(sessionId).run(wrapped, timeoutSeconds),
            )
        }
        return withEnvWarning(
            sandboxManager.shellFor(sessionId).run(
                command = wrapped,
                timeoutSeconds = timeoutSeconds,
                displayCommand = command,
            ),
        )
    }

    private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    val toolInfo = ToolInfo(
        id = "execute_shell_command",
        name = "Execute Shell Command",
        description = "Execute a shell command in the Linux sandbox",
        nameRes = Res.string.tool_execute_shell_command_name,
        descriptionRes = Res.string.tool_execute_shell_command_description,
        isEnabled = false,
        // Tiered availability: full proot Linux when the sandbox is Ready, host
        // mksh/toybox otherwise. The master switch is the sandbox toggle in
        // Settings → Agent, so a per-tool switch here would read back a setting
        // nothing consults.
        userToggleable = false,
    )
}

/** Shared parameter schema for both shell tiers (proot and native). */
private val shellParameters = mapOf(
    "command" to ParameterSchema("string", "The shell command to execute", true),
    "timeout" to ParameterSchema("integer", "Timeout in seconds (default 30, max 600)", false),
    "working_dir" to ParameterSchema("string", "If set, run the command starting in this directory (cd <dir> && <command>). The cd persists for subsequent calls — same as if the user had run cd themselves.", false),
    "env" to ParameterSchema("object", "Per-command environment variable overrides. Scoped to this call only; does not persist (use 'export' inside the command if you want persistence).", false),
    "background" to ParameterSchema("boolean", "Run detached as a background job. Returns a session_id; use manage_process to check status. Does not share the persistent shell.", false),
    "fresh" to ParameterSchema("boolean", "If true, run in a one-shot isolated shell that does not share state with the persistent session. Default false.", false),
)

/**
 * Description for the native tier: host mksh + toybox, no rootfs. Kept honest
 * about what's missing so the model doesn't waste turns on apt/python/ssh —
 * and points at the upgrade path (installing the sandbox transparently
 * promotes this same tool to the full Linux description above).
 */
private fun nativeToolDescription(homeDir: String): String = """Execute a shell command on the Android host (no Linux sandbox installed) and return stdout, stderr, exit code, and current working directory. The shell is mksh (`/system/bin/sh`) with the toybox applets: ls, cat, grep, sed, awk, find, xargs, tar, gzip, etc.

Shell session is PERSISTENT across calls within THIS conversation: cwd and exported variables carry over, other chats stay isolated. Home is the app-private directory `$homeDir` — start there; `working_dir` may be relative (to home) or absolute.

Discover the exact applet set on this device by running `toybox` with no arguments — that lists every command available; don't guess at ones from the list above. Files you create live in the same workspace the `read_file`/`write_file` tools use, so paths are interchangeable between the shell and the file tools.

NOT available in this tier — do not attempt, all will fail:
- No apt/apk, no package installs of any kind.
- No python/node/git/curl/wget/ssh/scp — those arrive with the Linux sandbox.
- No bash-isms (arrays, [[ ]], process substitution); write POSIX sh.
- Files you write under app storage cannot be executed (the OS refuses exec there) — produce text/data, not binaries or scripts you intend to run.
- Host-admin work (system settings, other apps, dumpsys) is the separate `privileged_shell` tool (Shizuku), not this shell.
- Fullscreen TUIs (top, vim, less, nano) WILL NOT WORK — no PTY. Use `ps`, `cat`, pipes.

Limits and behavior:
- Output is capped at 15000 characters per stream; for large output, pipe through head/tail.
- Default timeout: 30s, max: 600s (10 min).
- Set background=true to run detached (writes to its own session_id). Use manage_process to check on it.
- Set fresh=true for a one-shot isolated shell with no shared state.

Upgrade path: if the user installs the Linux sandbox (Settings > Tools), this same tool transparently becomes a full Debian/Alpine shell — same session model, plus packages, ssh, python, and /root."""
