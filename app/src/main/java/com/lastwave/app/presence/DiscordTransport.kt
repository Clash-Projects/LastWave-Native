package com.lastwave.app.presence

/**
 * Transport contract for a Discord presence connection.
 *
 * The desktop client speaks the Discord IPC protocol straight down a named pipe
 * or unix socket; on Android the Discord app hosts that endpoint itself and
 * only exposes it through the Discord Social SDK, so the transport is a
 * separate concern from the payload ([DiscordPresence]) and from the decision
 * to publish ([DiscordPresenceManager]).
 *
 * Every method is exception-safe and reports failure as a value: presence is a
 * decoration on playback, so a Discord that is missing, killed or refusing must
 * never surface as a crash or stall the player. A `false` from
 * [setActivity]/[clearActivity] means "drop the connection and reconnect on the
 * next evaluation".
 */
interface DiscordTransport {

    /** True while frames can still be handed over. */
    val isOpen: Boolean

    /** Pushes one activity. False on any transport failure. */
    fun setActivity(activity: kotlinx.serialization.json.JsonObject): Boolean

    /** Hides the card. Best-effort, false on failure. */
    fun clearActivity(): Boolean

    /** Releases the connection. Never throws. */
    fun close()
}