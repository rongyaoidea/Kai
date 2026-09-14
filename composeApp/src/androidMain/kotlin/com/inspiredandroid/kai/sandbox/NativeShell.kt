package com.inspiredandroid.kai.sandbox

import com.inspiredandroid.kai.smartTruncate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

private const val NATIVE_MAX_OUTPUT_LENGTH = 15_000
private const val NATIVE_SH = "/system/bin/sh"
private const val SENTINEL_PREFIX = "__kai_end__"

/**
 * Persistent mksh session on the Android host — the zero-install tier of
 * `execute_shell_command`, used whenever the proot sandbox is not Ready. No
 * rootfs, no download: commands run as the app uid through `/system/bin/sh`
 * (mksh) with the toybox applets (`ls`, `cat`, `grep`, `sed`, `awk`, `find`,
 * `tar`, …). Each session owns one live `sh` process, so `cd`/exports persist
 * per conversation exactly like the proot tier.
 *
 * Honest limits (also spelled out in the tool description the model sees):
 * no bash, no apt/apk, no python/node/git/curl/ssh client, no package installs,
 * and anything the agent writes under app storage cannot be executed (the OS
 * refuses `exec` on app-data files). Host-admin work belongs to
 * `privileged_shell` (Shizuku), not here.
 */
class NativeShellSession(home: File) {
    private val startDir: File = home.apply { mkdirs() }
    private val tmpDir: File = File(home, ".kai-tmp").apply { mkdirs() }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    private val currentSink = AtomicReference<Sink?>(null)

    private class Sink(
        val nonce: String,
        val stdoutBuf: StringBuilder = StringBuilder(),
        val stderrBuf: StringBuilder = StringBuilder(),
        val done: CompletableDeferred<ShellResult> = CompletableDeferred(),
    )

    private data class ShellResult(
        val exitCode: Int,
        val cwd: String,
        val shellDied: Boolean = false,
    )

    /**
     * Run a single command in the persistent shell. [command] arrives already
     * wrapped with any `cd`/env prefix by the caller — same contract as
     * [PersistentSandboxShell.run]. Suspends until the sentinel is observed,
     * the timeout fires, or the shell dies. Concurrent calls are serialized.
     */
    suspend fun run(command: String, timeoutSeconds: Long): Map<String, Any> = mutex.withLock {
        try {
            runLocked(command, timeoutSeconds)
        } catch (e: CancellationException) {
            reset()
            throw e
        }
    }

    /** Tear down the shell. Next [run] lazily restarts it. Idempotent. */
    fun close() = reset()

    private suspend fun runLocked(command: String, timeoutSeconds: Long): Map<String, Any> {
        ensureShell()
        var proc = process
        if (proc == null || !proc.isAlive) {
            reset()
            ensureShell()
            proc = process
        }
        if (proc == null || !proc.isAlive) {
            return errorMap("Native shell could not be started ($NATIVE_SH missing?). Install the Linux sandbox in Settings > Tools for the full shell.")
        }
        val nonce = randomHex()
        val sink = Sink(nonce = nonce)
        currentSink.set(sink)

        val cmdFile = File(tmpDir, ".kai_cmd_$nonce")
        try {
            cmdFile.writeText(command)
        } catch (e: Exception) {
            currentSink.set(null)
            return errorMap("Failed to stage command: ${e.message}")
        }

        // Source the staged file (reading needs no exec permission, executing
        // it would be refused on app storage), capture the exit, emit the
        // sentinel to stderr so stdout redirects can't swallow it.
        val line = ". ${sq(cmdFile.absolutePath)}; __kai_st=$?; rm -f ${sq(cmdFile.absolutePath)}; " +
            "printf '%s\\n' \"$SENTINEL_PREFIX $nonce $__kai_st $PWD\" >&2"
        try {
            val w = writer ?: throw IllegalStateException("shell has no stdin")
            w.write(line)
            w.newLine()
            w.flush()
        } catch (e: Exception) {
            currentSink.set(null)
            reset()
            return errorMap("Failed to write to native shell: ${e.message}")
        }

        val result = withTimeoutOrNull(timeoutSeconds.seconds) { sink.done.await() }
        if (result == null) {
            proc.destroy()
            val recovered = withTimeoutOrNull(2.seconds) { sink.done.await() }
            currentSink.set(null)
            if (recovered == null) {
                reset()
                return timeoutMap(sink, "Command timed out and shell was reset")
            }
            return buildResult(sink, recovered, timedOut = true)
        }
        currentSink.set(null)
        if (result.shellDied) {
            return buildResult(sink, result, shellDied = true)
        }
        return buildResult(sink, result)
    }

    private fun ensureShell() {
        val proc = process
        if (proc != null && proc.isAlive) return
        reset()
        val started: Process
        try {
            startDir.mkdirs()
            tmpDir.mkdirs()
            started = ProcessBuilder(NATIVE_SH).directory(startDir).redirectErrorStream(false).start()
        } catch (_: Exception) {
            return
        }
        process = started
        writer = started.outputStream.bufferedWriter()
        val outReader = started.inputStream.bufferedReader()
        val errReader = started.errorStream.bufferedReader()
        scope.launch { drainLines(outReader, ::dispatchStdout) }
        scope.launch { drainLines(errReader, ::dispatchStderr) }
        scope.launch {
            runCatching { started.waitFor() }
            onDeath()
        }
    }

    private fun drainLines(reader: BufferedReader, fn: (String) -> Unit) {
        try {
            while (true) {
                fn(reader.readLine() ?: break)
            }
        } catch (_: Exception) {
            // Stream closed by reset()/death — the watchdog completes the sink.
        }
    }

    private fun onDeath() {
        process = null
        runCatching { writer?.close() }
        writer = null
        currentSink.getAndSet(null)?.done?.complete(
            ShellResult(exitCode = -1, cwd = startDir.absolutePath, shellDied = true),
        )
    }

    private fun dispatchStdout(line: String) {
        val sink = currentSink.get() ?: return
        appendBounded(sink.stdoutBuf, line)
    }

    private fun dispatchStderr(line: String) {
        if (line.isEmpty()) return
        if (line.startsWith("$SENTINEL_PREFIX ")) {
            val parts = line.removePrefix("$SENTINEL_PREFIX ").split(' ', limit = 3)
            val sink = currentSink.get()
            if (sink != null && parts.size == 3 && parts[0] == sink.nonce) {
                val exit = parts[1].toIntOrNull() ?: -1
                sink.done.complete(ShellResult(exitCode = exit, cwd = parts[2]))
                return
            }
        }
        val sink = currentSink.get() ?: return
        appendBounded(sink.stderrBuf, line)
    }

    private fun reset() {
        currentSink.getAndSet(null)?.done?.complete(
            ShellResult(exitCode = -1, cwd = startDir.absolutePath, shellDied = true),
        )
        runCatching { writer?.close() }
        writer = null
        val proc = process
        process = null
        if (proc != null) {
            runCatching { proc.destroyForcibly() }
            runCatching { proc.inputStream.close() }
            runCatching { proc.errorStream.close() }
            runCatching { proc.outputStream.close() }
        }
    }

    private fun buildResult(sink: Sink, result: ShellResult, timedOut: Boolean = false, shellDied: Boolean = false): Map<String, Any> {
        val died = shellDied || result.shellDied
        val stderr = if (died) {
            val tail = sink.stderrBuf.toString()
            if (tail.isEmpty()) "Shell session ended" else "$tail\nShell session ended"
        } else {
            sink.stderrBuf.toString()
        }
        return mapOf(
            "success" to (!timedOut && !died && result.exitCode == 0),
            "stdout" to sink.stdoutBuf.toString().smartTruncate(NATIVE_MAX_OUTPUT_LENGTH),
            "stderr" to stderr.smartTruncate(NATIVE_MAX_OUTPUT_LENGTH),
            "exit_code" to if (timedOut) -1 else result.exitCode,
            "timed_out" to timedOut,
            "cwd" to result.cwd,
            "shell_died" to died,
        )
    }

    private fun timeoutMap(sink: Sink, stderr: String): Map<String, Any> = mapOf(
        "success" to false,
        "stdout" to sink.stdoutBuf.toString().smartTruncate(NATIVE_MAX_OUTPUT_LENGTH),
        "stderr" to (sink.stderrBuf.toString() + "\n" + stderr).smartTruncate(NATIVE_MAX_OUTPUT_LENGTH),
        "exit_code" to -1,
        "timed_out" to true,
        "cwd" to startDir.absolutePath,
        "shell_died" to true,
    )

    private fun errorMap(stderr: String): Map<String, Any> = mapOf(
        "success" to false,
        "stdout" to "",
        "stderr" to stderr,
        "exit_code" to -1,
        "timed_out" to false,
        "cwd" to startDir.absolutePath,
        "shell_died" to false,
    )
}

/**
 * Owns the native-tier home directory and the per-caller [NativeShellSession]
 * table, plus one-shot execution for `fresh` calls. Lives inside
 * [LinuxSandboxManager] so session teardown (`closeShell`) covers both tiers
 * with no extra Koin wiring.
 */
class NativeShells(filesDir: File) {
    val home: File = File(filesDir, "kai-native").apply { mkdirs() }

    private val sessions = mutableMapOf<String, NativeShellSession>()

    val isAvailable: Boolean get() = runCatching { File(NATIVE_SH).canExecute() }.getOrDefault(false)

    fun shellFor(sessionId: String): NativeShellSession = synchronized(sessions) {
        sessions.getOrPut(sessionId) { NativeShellSession(home) }
    }

    fun close(sessionId: String) {
        synchronized(sessions) { sessions.remove(sessionId) }?.close()
    }

    /** Resolve a `working_dir` argument: absolute stays, relative anchors at [home], garbage falls back to [home]. */
    fun resolveDir(requested: String?): File {
        if (requested.isNullOrBlank()) return home
        val file = File(requested)
        val target = if (file.isAbsolute) file else File(home, requested)
        return if (target.isDirectory) target else home
    }

    /**
     * One-shot execution outside any persistent session (the `fresh` flag).
     * No state carries in or out. Environment applies via the process
     * environment (cleaner than a prefix for a single call); the working
     * directory resolves through [resolveDir].
     */
    suspend fun runOneShot(
        command: String,
        timeoutSeconds: Long,
        workingDir: String?,
        envMap: Map<String, String>,
    ): Map<String, Any> = withContext(Dispatchers.IO) {
        val dir = resolveDir(workingDir)
        val process: Process
        try {
            process = ProcessBuilder(NATIVE_SH, "-c", command)
                .directory(dir)
                .apply { environment().putAll(envMap) }
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            return@withContext mapOf(
                "success" to false,
                "stdout" to "",
                "stderr" to "Failed to start native shell: ${e.message}",
                "exit_code" to -1,
                "timed_out" to false,
                "cwd" to dir.absolutePath,
                "shell_died" to false,
            )
        }
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val outReader = Thread { drainBounded(process.inputStream.bufferedReader(), outBuf) }
        val errReader = Thread { drainBounded(process.errorStream.bufferedReader(), errBuf) }
        outReader.isDaemon = true
        errReader.isDaemon = true
        outReader.start()
        errReader.start()
        val completed = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
        if (!completed) {
            runCatching { process.destroyForcibly() }
        }
        runCatching { outReader.join(5_000) }
        runCatching { errReader.join(5_000) }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        val exit = if (completed) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        mapOf(
            "success" to (completed && exit == 0),
            "stdout" to outBuf.toString().smartTruncate(NATIVE_MAX_OUTPUT_LENGTH),
            "stderr" to (if (completed) errBuf.toString() else errBuf.toString() + "\nCommand timed out").smartTruncate(NATIVE_MAX_OUTPUT_LENGTH),
            "exit_code" to exit,
            "timed_out" to !completed,
            "cwd" to dir.absolutePath,
            "shell_died" to false,
        )
    }

    private fun drainBounded(reader: BufferedReader, buf: StringBuilder) {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                synchronized(buf) {
                    if (buf.length >= NATIVE_MAX_OUTPUT_LENGTH) continue
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(line)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { reader.close() }
        }
    }
}

private fun appendBounded(buf: StringBuilder, line: String) {
    if (buf.length >= NATIVE_MAX_OUTPUT_LENGTH) return
    if (buf.isNotEmpty()) buf.append('\n')
    buf.append(line)
}

private fun sq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun randomHex(): String = (0 until 16).map { "0123456789abcdef".random() }.joinToString("")
