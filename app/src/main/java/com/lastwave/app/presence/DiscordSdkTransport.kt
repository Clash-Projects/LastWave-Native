package com.lastwave.app.presence

import android.os.Process
import com.discord.socialsdk.DiscordRpcClient
import com.discord.socialsdk.NativeCalls
import kotlinx.serialization.json.JsonObject

/**
 * Discord transport backed by the Discord Social SDK
 * (`discord_partner_sdk.aar`, vendored under `app/libs`).
 *
 * The desktop client hand-rolls the Discord IPC protocol over a
 * `discord-ipc-N` named pipe or unix socket, because the Discord **desktop**
 * client exposes that socket on the same machine. No such socket exists on a
 * phone, so the same protocol is reached here the only supported way: the
 * Social SDK binds to the Discord app's own `IDiscordRpcService` (declared in
 * the SDK's manifest) and speaks the identical wire format. Hence
 * [DiscordRpcClient.sendFrame] takes exactly the
 * `{"cmd":"SET_ACTIVITY","args":{"pid":…,"activity":…},"nonce":…}` JSON the
 * desktop build writes into its pipe — same frames, different carrier.
 *
 * No account token is involved. Presence is published as this application
 * (Discord sees only [DiscordPresence.APPLICATION_ID]) and requires the Discord
 * app to be installed and signed in — the platform equivalent of the desktop
 * client being open. Nothing here can read or affect the user's account.
 *
 * Every entry point swallows failures: a missing Discord app, a killed process
 * or an UnsatisfiedLinkError must degrade to silence, never to a crash on the
 * playback path.
 */
class DiscordSdkTransport private constructor(
    private val client: DiscordRpcClient,
) : DiscordTransport {

    override val isOpen: Boolean get() = true

    override fun setActivity(activity: JsonObject): Boolean =
        send(DiscordPresence.setActivityFrame(activity, Process.myPid(), nonce()))

    override fun clearActivity(): Boolean =
        send(DiscordPresence.clearActivityFrame(Process.myPid(), nonce()))

    override fun close() {
        runCatching { client.disconnect() }
    }

    private fun send(frame: String): Boolean = runCatching {
        client.sendFrame(frame)
        true
    }.getOrDefault(false)

    companion object {
        /**
         * Connects and completes the handshake, or returns null.
         *
         * Null is the normal outcome for most installs — Discord is simply not
         * installed — so it must stay cheap and silent; [DiscordPresenceManager]
         * throttles retries rather than polling per state change.
         */
        fun connectOrNull(): DiscordSdkTransport? {
            val client = runCatching {
                // Referencing the client first guarantees
                // libdiscord_partner_sdk.so is loaded before anything calls
                // into it (the SDK self-loads from its static initializer).
                DiscordRpcClient(DiscordPresence.APPLICATION_ID.toLong())
            }.getOrNull() ?: return null
            val installed = runCatching { NativeCalls.isDiscordAppInstalled() }.getOrDefault(false)
            if (!installed) return null
            return runCatching {
                DiscordSdkTransport(client).also { it.client.connect(Process.myPid().toLong()) }
            }.getOrNull()
        }

        /** Microseconds since the epoch, matching the desktop client's nonce. */
        private fun nonce(): String = "${System.currentTimeMillis() * 1000L}"
    }
}