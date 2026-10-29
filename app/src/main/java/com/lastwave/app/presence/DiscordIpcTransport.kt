package com.lastwave.app.presence

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.discord.socialsdk.rpc.IDiscordRpcCallback
import com.discord.socialsdk.rpc.IDiscordRpcConnection
import com.discord.socialsdk.rpc.IDiscordRpcService
import java.util.UUID
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
 *
 * Fix note (no-SDK path): the original build resolved the Discord package with
 * a single deprecated `resolveService(intent, 0)` call and bound with an
 * implicit intent, then swallowed every failure. On real devices that meant
 * "never connects, never logs". Discovery below enumerates each known Discord
 * package's exported services for anything with `rpc` in the class name first
 * (explicit [ComponentName] bind — immune to action-string drift), and only
 * then falls back to the documented action. Every failure branch logs at
 * `WARN` so a missing / signed-out / refusing Discord is diagnosable in
 * logcat instead of silent.
 */
class DiscordIpcTransport private constructor(
    private val context: Context,
    private val serviceConnection: ServiceConnection,
    private val rpcConnection: IDiscordRpcConnection,
) : DiscordTransport {

    @Volatile
    private var closed = false

    override val isOpen: Boolean
        get() = !closed && runCatching { rpcConnection.asBinder().isBinderAlive }.getOrDefault(false)

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
            .onFailure { Log.d(TAG, "Discord RPC disconnect ignored", it) }
        runCatching { context.unbindService(serviceConnection) }
            .onFailure { Log.d(TAG, "Unbind ignored during close", it) }
    }

    private fun send(frame: String): Boolean {
        if (!isOpen) return false
        return runCatching {
            rpcConnection.sendFrame(frame)
            true
        }.onFailure {
            Log.w(TAG, "Discord RPC sendFrame failed; dropping connection", it)
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "DiscordIpcTransport"
        private const val RPC_ACTION = "com.discord.socialsdk.rpc.IDiscordRpcService"
        private const val RPC_VERSION = "1"

        private val DISCORD_PACKAGES = listOf(
            "com.discord",
            "com.discord.canary",
            "com.discord.ptb",
            "com.discord.debug",
        )

        private data class BoundRpc(
            val binder: IBinder,
            val connection: ServiceConnection,
        )

        /**
         * Resolves which installed Discord package hosts the RPC service, or returns null.
         *
         * Kept for callers that only need a package name; prefer
         * [findRpcComponent] which returns an explicit component.
         */
        

        /**
         * Finds an explicit component for Discord's RPC service.
         *
         * Strategy, per package:
         * 1. Enumerate the package's exported services and take any class name
         *    containing "rpc" (handles action-string drift between Discord
         *    builds — the failure mode that made presence silently never start).
         * 2. Fall back to `queryIntentServices` / `resolveService` for the
         *    documented action with API-correct flags.
         */
        fun findRpcComponent(context: Context): ComponentName? {
            val pm = context.packageManager
            for (pkg in DISCORD_PACKAGES) {
                // 1. Explicit enumeration: immune to action-string renames.
                runCatching { exportedRpcComponent(pm, pkg) }.getOrNull()?.let { return it }
                // 2. Documented action, with package visibility from the
                // manifest <queries> block.
                runCatching { resolveRpcComponent(pm, pkg) }.getOrNull()?.let { return it }
            }
            return null
        }

        private fun exportedRpcComponent(pm: PackageManager, pkg: String): ComponentName? {
            val services = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).services
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SERVICES).services
            } ?: return null
            for (info in services) {
                if (!info.exported) continue
                val name = info.name.orEmpty()
                if (name.contains("rpc", ignoreCase = true)) {
                    return ComponentName(pkg, name)
                }
            }
            return null
        }

        private fun resolveRpcComponent(pm: PackageManager, pkg: String): ComponentName? {
            val intent = Intent(RPC_ACTION).setPackage(pkg)
            // Prefer query (returns ServiceInfo with the concrete class) so the
            // subsequent bind can be explicit.
            val queried = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
            }
            queried.firstOrNull()?.serviceInfo?.let {
                return ComponentName(it.packageName, it.name)
            }
            val resolved = if (Build.VERSION.SDK_INT >= 33) {
                pm.resolveService(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveService(intent, PackageManager.MATCH_ALL)
            }
            resolved?.serviceInfo?.let {
                return ComponentName(it.packageName, it.name)
            }
            return null
        }

        /**
         * Connects and handshakes with Discord's RPC service, or returns null.
         *
         * The service bind is explicit ([findRpcComponent]). The raw binder is
         * captured on the main-thread `onServiceConnected` callback, but the
         * blocking `connect()` handshake runs afterwards on the caller's
         * coroutine dispatcher (DiscordPresenceManager uses Dispatchers.Default)
         * — never on the main thread. A bind timeout always unbinds via
         * `invokeOnCancellation` so retries do not leak connections.
         */
        suspend fun connectOrNull(context: Context, timeoutMs: Long = 3_000L): DiscordIpcTransport? {
            val component = runCatching { findRpcComponent(context) }.getOrNull()
            if (component == null) {
                Log.w(TAG, "Discord RPC service not found (Discord missing, outdated, or queries blocked)")
                return null
            }
            val appContext = context.applicationContext
            val intent = Intent(RPC_ACTION).setComponent(component)

            // Phase 1: bind and capture the raw binder (main thread callback,
            // no blocking work here).
            val bound = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun safeResume(result: BoundRpc?) {
                        if (resumed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(result)
                        }
                    }

                    val serviceConnection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, serviceBinder: IBinder?) {
                            if (serviceBinder == null) {
                                Log.w(TAG, "Discord RPC onServiceConnected with null binder")
                                safeResume(null)
                                return
                            }
                            safeResume(BoundRpc(serviceBinder, this))
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            Log.d(TAG, "Discord RPC service disconnected")
                        }

                        override fun onBindingDied(name: ComponentName?) {
                            Log.w(TAG, "Discord RPC binding died")
                            safeResume(null)
                        }

                        override fun onNullBinding(name: ComponentName?) {
                            Log.w(TAG, "Discord RPC null binding")
                            safeResume(null)
                        }
                    }

                    val ok = runCatching {
                        appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                    }.onFailure {
                        Log.w(TAG, "Discord RPC bindService threw for $component", it)
                    }.getOrDefault(false)

                    if (!ok) {
                        Log.w(TAG, "Discord RPC bindService returned false for $component")
                        safeResume(null)
                        return@suspendCancellableCoroutine
                    }

                    continuation.invokeOnCancellation {
                        resumed.set(true)
                        runCatching { appContext.unbindService(serviceConnection) }
                            .onFailure { Log.d(TAG, "Unbind ignored on cancel", it) }
                    }
                }
            }

            if (bound == null) {
                Log.w(TAG, "Discord RPC bind timed out after ${timeoutMs}ms for $component")
                return null
            }

            // Phase 2: handshake off the main thread.
            val rpcService = IDiscordRpcService.Stub.asInterface(bound.binder)
            val callback = object : IDiscordRpcCallback.Stub() {
                override fun onFrame(frame: String?) {}
                override fun onClose(code: Int, message: String?) {
                    Log.d(TAG, "Discord RPC closed: $code - $message")
                }
            }
            val rpcConn = runCatching {
                rpcService.connect(
                    DiscordPresence.APPLICATION_ID.toLong(),
                    RPC_VERSION,
                    callback,
                )
            }.onFailure {
                Log.w(TAG, "Discord RPC connect() failed for appId=${DiscordPresence.APPLICATION_ID}", it)
            }.getOrNull()

            if (rpcConn == null) {
                Log.w(TAG, "Discord RPC connect() returned null for $component; unbinding")
                runCatching { appContext.unbindService(bound.connection) }
                    .onFailure { Log.d(TAG, "Unbind ignored after failed connect", it) }
                return null
            }

            Log.d(TAG, "Discord RPC connected via $component")
            return DiscordIpcTransport(appContext, bound.connection, rpcConn)
        }

        private fun nonce(): String = UUID.randomUUID().toString()
    }
}
