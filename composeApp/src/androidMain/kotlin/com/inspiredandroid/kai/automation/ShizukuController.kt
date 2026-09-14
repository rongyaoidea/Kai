package com.inspiredandroid.kai.automation

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * ADB-level control through Shizuku. Shizuku itself is started by the user once
 * (wireless debugging or root); after that commands run in [PrivilegedShellService],
 * which the Shizuku server starts inside its own privileged process (shell uid,
 * or root when Shizuku runs rooted) — the same privilege `adb shell` has.
 *
 * Authorization uses Shizuku's non-Activity listener, so no Activity plumbing is
 * needed: [ensureReady] requests approval on first use and suspends until the
 * user answers (or the timeout hits). The privileged service connection is bound
 * once and reused; [release] drops it (e.g. when the feature is switched off).
 */
class ShizukuController(private val context: Context) {

    enum class State {
        NOT_INSTALLED,
        NOT_RUNNING,
        NEED_PERMISSION,
        READY,
    }

    data class ProcResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    @Volatile
    private var shellBinder: IBinder? = null

    @Volatile
    private var binding: ServiceConnection? = null

    private val bindLock = Any()

    fun state(): State {
        if (!isShizukuInstalled()) return State.NOT_INSTALLED
        if (!Shizuku.pingBinder()) return State.NOT_RUNNING
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            State.READY
        } else {
            State.NEED_PERMISSION
        }
    }

    fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun version(): Int = try {
        if (Shizuku.pingBinder()) Shizuku.getVersion() else -1
    } catch (_: Throwable) {
        -1
    }

    fun release() {
        val conn = synchronized(bindLock) {
            val current = binding
            binding = null
            shellBinder = null
            current
        }
        if (conn != null) {
            try {
                Shizuku.unbindUserService(userServiceArgs(), conn, true)
            } catch (_: Throwable) {
            }
        }
    }

    suspend fun ensureReady(requestTimeoutMs: Long = 60_000L) {
        when (state()) {
            State.READY -> return

            State.NOT_INSTALLED -> throw ShizukuUnavailableException(
                "Shizuku is not installed. Install it, start the service via wireless debugging or root, then retry.",
            )

            State.NOT_RUNNING -> throw ShizukuUnavailableException(
                "The Shizuku service is not running. Start it (Shizuku app → Start), then retry.",
            )

            State.NEED_PERMISSION -> requestPermission(requestTimeoutMs)
        }
    }

    private suspend fun requestPermission(timeoutMs: Long) {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
        try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val listener = object : Shizuku.OnRequestPermissionResultListener {
                        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                            Shizuku.removeRequestPermissionResultListener(this)
                            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                if (cont.isActive) cont.resume(Unit)
                            } else {
                                if (cont.isActive) cont.resumeWith(Result.failure(ShizukuDeniedException()))
                            }
                        }
                    }
                    Shizuku.addRequestPermissionResultListener(listener)
                    cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
                    try {
                        Shizuku.requestPermission(REQUEST_CODE_AUTOMATION)
                    } catch (t: Throwable) {
                        Shizuku.removeRequestPermissionResultListener(listener)
                        if (cont.isActive) cont.resumeWith(Result.failure(t))
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw ShizukuDeniedException()
        }
    }

    /**
     * Run [argv] in the privileged service process. No shell is implied — wrap
     * with `sh -c` yourself when you need pipes or redirection.
     */
    suspend fun exec(
        argv: Array<String>,
        timeoutMs: Long = 30_000L,
        maxOutputBytes: Int = PrivilegedShellService.DEFAULT_MAX_OUTPUT_BYTES,
    ): ProcResult = withContext(Dispatchers.IO) {
        ensureReady()
        val startMs = android.os.SystemClock.elapsedRealtime()
        fun remainingMs(): Long = (timeoutMs - (android.os.SystemClock.elapsedRealtime() - startMs)).coerceAtLeast(5_000L)
        val binder = try {
            boundShellBinder(remainingMs().coerceAtLeast(10_000L))
        } catch (e: ShizukuUnavailableException) {
            // One retry with a fresh connection: the Shizuku server
            // occasionally drops the first bind (process start race) while
            // staying healthy for every other app.
            Log.w(TAG, "privileged bind attempt 1 failed, retrying", e)
            dropBinding()
            boundShellBinder(remainingMs().coerceAtLeast(10_000L))
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PrivilegedShellService.DESCRIPTOR)
            data.writeStringArray(argv)
            data.writeLong(remainingMs().coerceIn(1_000L, MAX_EXEC_TIMEOUT_MS))
            data.writeLong(maxOutputBytes.toLong().coerceIn(4_096L, PrivilegedShellService.MAX_OUTPUT_BYTES_HARD_CAP.toLong()))
            val ok = try {
                binder.transact(PrivilegedShellService.TRANSACT_EXEC, data, reply, 0)
            } catch (t: Throwable) {
                dropBinding()
                throw ShizukuUnavailableException("privileged service call failed: ${t.message}")
            }
            if (!ok) {
                dropBinding()
                throw ShizukuUnavailableException("privileged service rejected the call")
            }
            reply.readException()
            ProcResult(
                exitCode = reply.readInt(),
                stdout = reply.readString().orEmpty(),
                stderr = reply.readString().orEmpty(),
            )
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedShellService::class.java.name),
    ).daemon(false)
        .version(USER_SERVICE_VERSION)
        .processNameSuffix(USER_SERVICE_PROCESS_SUFFIX)
        .tag(PrivilegedShellService.USER_SERVICE_TAG)

    private suspend fun boundShellBinder(timeoutMs: Long): IBinder {
        shellBinder?.let { if (it.isBinderAlive) return it }
        return synchronized(bindLock) {
            val alive = shellBinder?.takeIf { it.isBinderAlive }
            if (alive != null) return@synchronized alive
            null
        } ?: bindShellService(timeoutMs)
    }

    private suspend fun bindShellService(timeoutMs: Long): IBinder {
        Log.d(TAG, "requesting privileged user service bind (timeout=${timeoutMs}ms)${serverInfo()}")
        return bindShellServiceInner(timeoutMs)
    }

    private suspend fun bindShellServiceInner(timeoutMs: Long): IBinder = try {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine<IBinder> { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (service != null && service.isBinderAlive) {
                            Log.d(TAG, "privileged user service connected")
                            synchronized(bindLock) {
                                shellBinder = service
                                binding = this
                            }
                            if (cont.isActive) cont.resume(service)
                        } else if (cont.isActive) {
                            cont.resumeWith(
                                Result.failure(
                                    ShizukuUnavailableException("privileged service connected with a dead binder"),
                                ),
                            )
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        dropBinding()
                    }
                }
                try {
                    Shizuku.bindUserService(userServiceArgs(), conn)
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWith(Result.failure(t))
                }
                cont.invokeOnCancellation { dropBinding() }
            }
        }
    } catch (_: TimeoutCancellationException) {
        dropBinding()
        Log.w(TAG, "privileged user service bind timed out${serverInfo()}")
        throw ShizukuUnavailableException(
            "binding the privileged service timed out — the Shizuku server accepted the request " +
                "but never started Kai's privileged process${serverInfo()}. " +
                "Restart the Shizuku service, re-grant Kai, and retry. " +
                "If other apps work, capture `adb logcat -s KaiShizuku KaiPrivShell:* Shizuku:*` during a retry.",
        )
    }

    /** Shizuku server identity for diagnostics; never throws. */
    private fun serverInfo(): String = try {
        " (shizuku server v${Shizuku.getVersion()}, uid ${Shizuku.getUid()})"
    } catch (_: Throwable) {
        ""
    }

    private fun dropBinding() {
        val conn = synchronized(bindLock) {
            val current = binding
            binding = null
            shellBinder = null
            current
        }
        if (conn != null) {
            try {
                Shizuku.unbindUserService(userServiceArgs(), conn, true)
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TAG = "KaiShizuku"
        private const val REQUEST_CODE_AUTOMATION = 4417

        /**
         * Remote process name suffix (`<package>:privshell`). Required: the
         * Shizuku API rejects [Shizuku.UserServiceArgs] without one
         * ("process name suffix must not be null").
         */
        private const val USER_SERVICE_PROCESS_SUFFIX = "privshell"

        /**
         * Bump when [PrivilegedShellService]'s protocol changes so Shizuku
         * recreates the remote service instead of reusing a stale one.
         */
        private const val USER_SERVICE_VERSION = 2

        /** Upper bound for a single privileged command (matches `privileged_shell`). */
        private const val MAX_EXEC_TIMEOUT_MS = 600_000L
    }
}

/**
 * Absolute path for framework/toybox binaries executed in the privileged
 * process. That process is spawned by the Shizuku server (not by an app
 * runtime), so its PATH is minimal and not to be trusted — every binary Kai
 * invokes there (`sh`, `input`, `uiautomator`, `dumpsys`, …) lives in
 * `/system/bin` on all Android releases.
 */
internal fun systemBin(name: String): String = "/system/bin/$name"

class ShizukuUnavailableException(message: String) : Exception(message)

class ShizukuDeniedException : Exception("Shizuku authorization was denied or timed out. Approve Kai in the Shizuku app, then retry.")
