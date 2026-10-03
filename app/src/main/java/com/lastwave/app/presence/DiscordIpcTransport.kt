package com.lastwave.app.presence

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.discord.socialsdk.rpc.IDiscordRpcCallback
import com.discord.socialsdk.rpc.IDiscordRpcConnection
import com.discord.socialsdk.rpc.IDiscordRpcService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume

/**
 * Discord transport connecting directly to Discord's Android IPC service
 * (`com.discord.socialsdk.rpc.IDiscordRpcService`) over standard Android AIDL.
 *
 * This speaks the identical wire protocol as the desktop client
 * (`{"cmd":"SET_ACTIVITY","args":{"pid":…,"activity":…},"nonce":…}`), but
 * binds natively via Android's IPC instead of bundling Discord's closed
 * native C++ / WebRTC partner SDK.
 *
 * Benefits:
 * - 0 MB overhead (no vendored native `.so` or `.aar` binaries).
 * - Zero extra permissions (no microphone or audio recording permissions).
 * - Zero native crash risk (no JNI pointer dereferencing or SIGSEGV).
 * - Compatible with all Discord Android builds (stable, canary, ptb, debug).
 *
 * No user token is involved; presence is published as this application ID.
 */
class DiscordIpcTransport private constructor(
    private val context: Context,
    private val serviceConnection: ServiceConnection,
    private val rpcConnection: IDiscordRpcConnection,
) : DiscordTransport {

    @Volatile
    private var closed = false

    override val isOpen: Boolean
        get() = !closed &&
            rpcConnection.asBinder().isBinderAlive &&
            rpcConnection.asBinder().pingBinder()

    override fun setActivity(activity: JsonObject): Boolean {
        if (!isOpen) return false
        val frame = DiscordPresence.setActivityFrame(activity, Process.myPid(), nonce())
        return send(frame)
    }

    override fun clearActivity(): Boolean {
        if (!isOpen) return false
        val frame = DiscordPresence.clearActivityFrame(Process.myPid(), nonce())
        return send(frame)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { rpcConnection.disconnect() }
        runCatching { context.unbindService(serviceConnection) }
            .onFailure { Log.d(TAG, "Unbind ignored during close", it) }
    }

    private fun send(frame: String): Boolean = runCatching {
        if (!isOpen) return false
        rpcConnection.sendFrame(frame)
        true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "DiscordIpcTransport"
        private const val RPC_ACTION = "com.discord.socialsdk.rpc.IDiscordRpcService"

        private val DISCORD_PACKAGES = listOf(
            "com.discord",
            "com.discord.canary",
            "com.discord.ptb",
            "com.discord.debug",
        )

        /**
         * Resolves which installed Discord package hosts the RPC service, or returns null.
         */
        fun findDiscordPackage(context: Context): String? {
            val pm = context.packageManager
            for (pkg in DISCORD_PACKAGES) {
                val intent = Intent(RPC_ACTION).setPackage(pkg)
                if (runCatching { pm.resolveService(intent, 0) }.getOrNull() != null) {
                    return pkg
                }
            }
            return null
        }

        /**
         * Connects and handshakes with Discord's RPC service, or returns null.
         */
        suspend fun connectOrNull(context: Context, timeoutMs: Long = 3_000L): DiscordIpcTransport? {
            val pkg = runCatching { findDiscordPackage(context) }.getOrNull() ?: return null
            val appContext = context.applicationContext
            val intent = Intent(RPC_ACTION).setPackage(pkg)

            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val serviceConnection = object : ServiceConnection {
                        @Volatile
                        private var resumed = false

                        override fun onServiceConnected(name: ComponentName?, serviceBinder: IBinder?) {
                            if (resumed) return
                            if (serviceBinder == null) {
                                safeResume(null)
                                return
                            }
                            val rpcService = IDiscordRpcService.Stub.asInterface(serviceBinder)
                            val rpcConn = runCatching {
                                rpcService.connect(
                                    DiscordPresence.APPLICATION_ID.toLong(),
                                    "1",
                                    object : IDiscordRpcCallback.Stub() {
                                        override fun onFrame(frame: String?) {}
                                        override fun onClose(code: Int, message: String?) {
                                            Log.d(TAG, "Discord RPC closed: $code - $message")
                                        }
                                    },
                                )
                            }.getOrNull()

                            if (rpcConn != null) {
                                safeResume(DiscordIpcTransport(appContext, this, rpcConn))
                            } else {
                                runCatching { appContext.unbindService(this) }
                                safeResume(null)
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            Log.d(TAG, "Discord RPC service disconnected")
                        }

                        override fun onBindingDied(name: ComponentName?) {
                            safeResume(null)
                        }

                        override fun onNullBinding(name: ComponentName?) {
                            safeResume(null)
                        }

                        private fun safeResume(transport: DiscordIpcTransport?) {
                            if (!resumed && continuation.isActive) {
                                resumed = true
                                continuation.resume(transport)
                            }
                        }
                    }

                    val bound = runCatching {
                        appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                    }.getOrDefault(false)

                    if (!bound) {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                        return@suspendCancellableCoroutine
                    }

                    continuation.invokeOnCancellation {
                        runCatching { appContext.unbindService(serviceConnection) }
                    }
                }
            }
        }

        private fun nonce(): String = "${System.currentTimeMillis() * 1000L}"
    }
}
