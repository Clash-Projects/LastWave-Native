/*
 * Copyright (C) 2026 LastWave Authors (https://github.com/Clash-Projects/LastWave-Native)
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This file is part of LastWave.
 *
 * LastWave is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * LastWave is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LastWave. If not, see <https://www.gnu.org/licenses/>.
 */

package com.lastwave.app.presence

import kotlinx.serialization.json.JsonObject

/**
 * Transport contract for a Discord presence connection.
 *
 * The desktop client speaks the Discord IPC protocol straight down a named pipe
 * or unix socket; on Android the Discord app hosts that endpoint itself and
 * exposes it through its RPC service (IDiscordRpcService), so the transport is a
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
    fun setActivity(activity: JsonObject): Boolean

    /** Hides the card. Best-effort, false on failure. */
    fun clearActivity(): Boolean

    /** Releases the connection. Never throws. */
    fun close()
}
