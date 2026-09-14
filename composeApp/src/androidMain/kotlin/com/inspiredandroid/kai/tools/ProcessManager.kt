package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SandboxState
import org.koin.java.KoinJavaComponent.inject
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val NATIVE_BG_MAX_OUTPUT = 15_000

class ProcessManager(private val sandboxManager: LinuxSandboxManager) {

    class Session(
        val id: String,
        val command: String,
        val startTime: Long,
        @Volatile var stdout: String = "",
        @Volatile var stderr: String = "",
        @Volatile var finished: Boolean = false,
        @Volatile var exitCode: Int? = null,
        @Volatile var timedOut: Boolean = false,
        /** Live proot process, so kill can actually terminate it. */
        @Volatile var process: java.lang.Process? = null,
        /** Set by kill before the process handle exists; the start callback honors it. */
        @Volatile var cancelled: Boolean = false,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val nextId = AtomicInteger(1)

    fun startBackground(
        command: String,
        timeoutSeconds: Long,
        workingDir: String,
        envMap: Map<String, String>,
    ): Map<String, Any> {
        val sessionId = "bg-${nextId.getAndIncrement()}"
        val session = Session(
            id = sessionId,
            command = command,
            startTime = System.currentTimeMillis(),
        )
        sessions[sessionId] = session

        // Tiered execution, mirroring ShellCommandTool: proot when the sandbox
        // is Ready, host mksh/toybox otherwise. The session table (and therefore
        // the manage_process surface) is shared across both tiers.
        val ready = runCatching { sandboxManager.state.value is SandboxState.Ready }.getOrDefault(false)
        if (!ready) {
            if (!runCatching { sandboxManager.isNativeShellAvailable() }.getOrDefault(false)) {
                sessions.remove(sessionId)
                return mapOf("success" to false, "error" to "No shell available: install the Linux sandbox in Settings > Tools.")
            }
            startNativeBackground(session, command, timeoutSeconds, workingDir, envMap)
            return mapOf(
                "success" to true,
                "session_id" to sessionId,
                "status" to "running",
                "message" to "Process started in background (native shell, no sandbox). Use manage_process tool to check status.",
            )
        }

        val executor = try {
            sandboxManager.createProotExecutor()
        } catch (e: Exception) {
            sessions.remove(sessionId)
            return mapOf("success" to false, "error" to (e.message ?: "Sandbox is not available"))
        }
        CompletableFuture.runAsync {
            try {
                val result = executor.execute(command, timeoutSeconds, workingDir, envMap) { process ->
                    session.process = process
                    // kill() may race the spawn; honoring the flag here closes that window.
                    if (session.cancelled) runCatching { process.destroyForcibly() }
                }
                if (!session.cancelled) {
                    session.stdout = result["stdout"] as? String ?: ""
                    session.stderr = result["stderr"] as? String ?: ""
                    session.exitCode = result["exit_code"] as? Int ?: -1
                    session.timedOut = result["timed_out"] as? Boolean ?: false
                }
            } catch (e: Exception) {
                if (!session.cancelled) {
                    session.stderr = e.message ?: "Failed to start process"
                    session.exitCode = -1
                }
            } finally {
                session.finished = true
            }
        }

        return mapOf(
            "success" to true,
            "session_id" to sessionId,
            "status" to "running",
            "message" to "Process started in background. Use manage_process tool to check status.",
        )
    }

    fun list(): Map<String, Any> {
        val running = sessions.values.filter { !it.finished }.map { it.toInfo() }
        val finished = sessions.values.filter { it.finished }.map { it.toInfo() }
        return mapOf(
            "running" to running,
            "finished" to finished,
            "total" to sessions.size,
        )
    }

    fun log(sessionId: String, offset: Int, limit: Int): Map<String, Any> {
        val session = sessions[sessionId]
            ?: return mapOf("success" to false, "error" to "Unknown session: $sessionId")

        val stdoutLines = session.stdout.lines()
        val sliced = stdoutLines.drop(offset).take(limit).joinToString("\n")

        return mapOf(
            "success" to true,
            "session_id" to sessionId,
            "status" to if (session.finished) "finished" else "running",
            "exit_code" to (session.exitCode ?: -1),
            "stdout" to sliced,
            "stderr" to session.stderr.takeLast(2000),
            "total_stdout_lines" to stdoutLines.size,
            "offset" to offset,
            "timed_out" to session.timedOut,
        )
    }

    fun kill(sessionId: String): Map<String, Any> {
        val session = sessions[sessionId]
            ?: return mapOf("success" to false, "error" to "Unknown session: $sessionId")

        if (session.finished) {
            return mapOf("success" to true, "message" to "Process already finished", "exit_code" to (session.exitCode ?: -1))
        }

        // Mark first so the finish callback doesn't overwrite the outcome, then
        // actually destroy the proot process. If it hasn't spawned yet, the
        // start callback sees `cancelled` and destroys it immediately.
        session.cancelled = true
        session.finished = true
        session.exitCode = -1
        session.timedOut = true
        session.process?.let { process ->
            // Note: killing proot doesn't reliably reap guest children (same
            // limitation as the timeout path in ProotLauncher); the process is
            // gone and its output is frozen, which is what the tool promises.
            runCatching { process.destroyForcibly() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
        return mapOf("success" to true, "message" to "Process terminated")
    }

    fun remove(sessionId: String): Map<String, Any> {
        val session = sessions[sessionId]
            ?: return mapOf("success" to false, "error" to "Unknown session: $sessionId")
        if (!session.finished) {
            return mapOf(
                "success" to false,
                "error" to "Session $sessionId is still running — kill it before removing it",
            )
        }
        sessions.remove(sessionId)
        return mapOf("success" to true, "message" to "Session removed")
    }

    private fun Session.toInfo(): Map<String, Any> = mapOf(
        "session_id" to id,
        "command" to command,
        "status" to if (finished) "finished" else "running",
        "exit_code" to (exitCode ?: -1),
        "duration_seconds" to ((System.currentTimeMillis() - startTime) / 1000),
        "timed_out" to timedOut,
        "stdout_length" to stdout.length,
    )

    /**
     * Native one-shot background job: host `/system/bin/sh` via ProcessBuilder,
     * mirroring the desktop ProcessManager. Environment applies through the
     * process environment; the working directory resolves against the native
     * home (absolute stays, relative anchors there, garbage falls back to it).
     */
    private fun startNativeBackground(
        session: Session,
        command: String,
        timeoutSeconds: Long,
        workingDir: String,
        envMap: Map<String, String>,
    ) {
        CompletableFuture.runAsync {
            try {
                val home = sandboxManager.nativeHome
                val dir = when {
                    workingDir.isBlank() -> home
                    else -> {
                        val candidate = File(workingDir).let { f -> if (f.isAbsolute) f else File(home, workingDir) }
                        if (candidate.isDirectory) candidate else home
                    }
                }
                val process = ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(dir)
                    .apply { environment().putAll(envMap) }
                    .start()
                session.process = process
                // kill() may race the spawn; honoring the flag here closes that window.
                if (session.cancelled) {
                    runCatching { process.destroyForcibly() }
                    session.finished = true
                    return@runAsync
                }
                val outBuf = StringBuilder()
                val errBuf = StringBuilder()
                val outDrain = CompletableFuture.runAsync {
                    drainBounded(process.inputStream, outBuf) { session.stdout = it }
                }
                val errDrain = CompletableFuture.runAsync {
                    drainBounded(process.errorStream, errBuf) { session.stderr = it }
                }
                val completed = try {
                    process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    false
                }
                runCatching { outDrain.get(5, TimeUnit.SECONDS) }
                runCatching { errDrain.get(5, TimeUnit.SECONDS) }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
                if (!session.cancelled) {
                    if (!completed) {
                        runCatching { process.destroyForcibly() }
                        session.timedOut = true
                        session.exitCode = -1
                    } else {
                        session.exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
                    }
                    session.stdout = outBuf.toString()
                    session.stderr = errBuf.toString()
                }
            } catch (e: Exception) {
                if (!session.cancelled) {
                    session.stderr = e.message ?: "Failed to start process"
                    session.exitCode = -1
                }
            } finally {
                session.finished = true
            }
        }
    }

    private fun drainBounded(stream: InputStream, buf: StringBuilder, publish: (String) -> Unit) {
        try {
            stream.bufferedReader().forEachLine { line ->
                synchronized(buf) {
                    if (buf.length < NATIVE_BG_MAX_OUTPUT) {
                        if (buf.isNotEmpty()) buf.append('\n')
                        buf.append(line)
                        publish(buf.toString())
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { stream.close() }
        }
    }
}

/**
 * Android's background processes run inside the proot sandbox, so the manager needs the
 * sandbox to hand it an executor. See the shared `ProcessManagerTool` in `src/jvmShared`.
 */
internal fun createProcessManager(): ProcessManager {
    val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)
    return ProcessManager(sandboxManager)
}
