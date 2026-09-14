package com.inspiredandroid.kai.automation

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Runs shell commands inside Shizuku's privileged process (shell uid, or root
 * when Shizuku runs rooted).
 *
 * **This class must implement [IBinder], not extend `android.app.Service`.**
 * Shizuku's server loads the class out of Kai's APK and instantiates it
 * directly in its own process — there is no Android service binding, so
 * `Service.onBind` is never called and the manifest is never consulted. The
 * server then does `(IBinder) serviceClass.newInstance()`, so anything that is
 * not already an `IBinder` fails that cast, the user service never starts, and
 * the client sees `binding the privileged service timed out`.
 *
 * It also implements the reserved destroy transaction (`16777115`), which
 * Shizuku invokes when replacing or removing the service, and which must end
 * the process — otherwise a stale `:privshell` process lingers with shell/root
 * privilege.
 *
 * Protocol ([TRANSACT_EXEC]): request is `String[] argv`, `long timeoutMs`,
 * `long maxOutputBytes`; reply is `int exitCode`, `String stdout` (capped),
 * `String stderr` (capped). `maxOutputBytes` bounds one stream — tens of KB for
 * text callers, megabytes for screenshot base64.
 *
 * No DI and no app singletons here: `Application.onCreate` and Koin do not run
 * in the Shizuku process.
 */
class PrivilegedShellService : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == TRANSACTION_DESTROY) {
            Log.i(TAG, "destroy requested; exiting privileged process")
            // Shizuku expects the process to end here so it does not linger.
            Process.killProcess(Process.myPid())
            return true
        }
        if (code != TRANSACT_EXEC) return super.onTransact(code, data, reply, flags)

        data.enforceInterface(DESCRIPTOR)
        val argv = data.createStringArray()?.toList().orEmpty()
        val timeoutMs = data.readLong().coerceIn(1_000L, MAX_COMMAND_TIMEOUT_MS)
        val maxBytes = if (data.dataAvail() > 0) {
            data.readLong().toInt().coerceIn(4_096, MAX_OUTPUT_BYTES_HARD_CAP)
        } else {
            DEFAULT_MAX_OUTPUT_BYTES
        }
        Log.d(TAG, "exec ${argv.firstOrNull()} (timeout=${timeoutMs}ms, cap=$maxBytes)")
        val result = runCommand(argv, timeoutMs, maxBytes)
        reply?.writeNoException()
        reply?.writeInt(result.exitCode)
        reply?.writeString(result.stdout)
        reply?.writeString(result.stderr)
        return true
    }

    private data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runCommand(argv: List<String>, timeoutMs: Long, maxBytes: Int): ExecResult {
        if (argv.isEmpty()) return ExecResult(2, "", "empty argv")
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(false).start()
        } catch (t: Throwable) {
            return ExecResult(127, "", "could not start ${argv.firstOrNull()}: ${t.message}")
        }
        return try {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val outThread = Thread({ drainCapped(process.inputStream, stdout, maxBytes) }, "privshell-out")
            val errThread = Thread({ drainCapped(process.errorStream, stderr, maxBytes) }, "privshell-err")
            outThread.isDaemon = true
            errThread.isDaemon = true
            outThread.start()
            errThread.start()
            // Close stdin: privileged shell commands never read from the agent.
            try {
                process.outputStream.close()
            } catch (_: Throwable) {
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ExecResult(124, readCapped(stdout, maxBytes), readCapped(stderr, maxBytes) + "\n[timed out after ${timeoutMs}ms]")
            }
            outThread.join(2_000)
            errThread.join(2_000)
            ExecResult(process.exitValue(), readCapped(stdout, maxBytes), readCapped(stderr, maxBytes))
        } finally {
            try {
                process.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    private fun drainCapped(input: java.io.InputStream, out: ByteArrayOutputStream, cap: Int) {
        val buffer = ByteArray(8_192)
        try {
            while (true) {
                if (out.size() >= cap) {
                    if (input.read(buffer) <= 0) break else continue
                }
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, minOf(read, cap - out.size()))
            }
        } catch (_: Throwable) {
        }
    }

    private fun readCapped(out: ByteArrayOutputStream, maxChars: Int): String = out.toString(Charsets.UTF_8.name()).take(maxChars)

    companion object {
        private const val TAG = "KaiPrivShell"
        const val DESCRIPTOR = "kai.PrivilegedShellService"
        const val TRANSACT_EXEC = IBinder.FIRST_CALL_TRANSACTION

        /** Shizuku's reserved destroy transaction for user services. */
        const val TRANSACTION_DESTROY = 16777115

        const val DEFAULT_MAX_OUTPUT_BYTES = 64_000
        const val MAX_OUTPUT_BYTES_HARD_CAP = 8_000_000

        /** Upper bound for a single privileged command (matches `privileged_shell`). */
        const val MAX_COMMAND_TIMEOUT_MS = 600_000L

        /**
         * Stable tag Shizuku keys the user service by. Without it, Shizuku uses
         * the class name, which R8 can rename — the service then looks brand new
         * on every app update and old privileged processes are never reclaimed.
         */
        const val USER_SERVICE_TAG = "kai-privshell"
    }
}
