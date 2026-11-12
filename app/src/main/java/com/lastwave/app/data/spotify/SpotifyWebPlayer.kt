package com.lastwave.app.data.spotify

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * What the web player itself is built on, read out of its own script.
 *
 * Three things live in there and all three are needed to talk to Spotify the way the
 * web player does, and all three move when Spotify rebuilds it:
 *
 * 1. The **persisted-query hashes**. Every GraphQL operation is named by a hash, and the
 *    hash changes when the operation is rebuilt. An operation that is not declared any
 *    more is refused with "persisted query not found", which is how a search that used
 *    to work stops working without anything in the app changing.
 * 2. The **one-time-password secret** and the **version** it belongs to. The token
 *    exchange is signed with a one-time password derived from them, and a secret that
 *    has been retired produces a request that is refused.
 * 3. The **canvas operation**, which is a persisted query like the rest.
 *
 * Reading them from the player rather than from a copy of them is the difference
 * between working and not: a copy goes stale the moment the player is rebuilt, and
 * nothing in the app notices until every request with it starts being refused.
 */
object SpotifyWebPlayer {

    private const val TAG = "SpotifyWebPlayer"
    private const val PLAYER_PAGE = "https://open.spotify.com/"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** How long a read of the player is good for. The player is rebuilt every few weeks. */
    private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000

    /** The player's bundles are large; only the first few are read. */
    private const val MAX_SCRIPTS = 4

    @Volatile private var script: String? = null
    @Volatile private var readAt = 0L
    private val lock = Mutex()

    /**
     * The one-time-password secret and the version it belongs to.
     */
    data class Secret(val value: String, val version: Int)

    /**
     * The player's script, or null when it could not be read.
     *
     * The result is remembered for a few hours: the player changes rarely, and the
     * script is several megabytes.
     */
    suspend fun script(okHttpClient: OkHttpClient, forceRefresh: Boolean = false): String? {
        val current = script
        val age = System.currentTimeMillis() - readAt
        if (!forceRefresh && current != null && readAt != 0L && age < CACHE_TTL_MS) {
            return current
        }
        return lock.withLock {
            val again = script
            val againAge = System.currentTimeMillis() - readAt
            if (!forceRefresh && again != null && readAt != 0L && againAge < CACHE_TTL_MS) {
                return@withLock again
            }
            val found = read(okHttpClient)
            readAt = System.currentTimeMillis()
            script = found
            found
        }
    }

    private fun read(okHttpClient: OkHttpClient): String? {
        val page = get(okHttpClient, PLAYER_PAGE) ?: return null
        val bundles = scripts(page)
        if (bundles.isEmpty()) {
            Log.w(TAG, "The player page named no bundles")
            return null
        }
        for (bundle in bundles.take(MAX_SCRIPTS)) {
            val body = get(okHttpClient, bundle) ?: continue
            // The hashes and the secret are both in the player's own bundle; the first
            // one that has either is the one worth keeping.
            if (queryHash(body, CANVAS_OPERATION) != null || secrets(body).isNotEmpty()) {
                return body
            }
        }
        Log.w(TAG, "None of the player's bundles carried a query hash or a secret")
        return null
    }

    private fun get(okHttpClient: OkHttpClient, url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", PLAYER_PAGE)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read $url: ${e.message}")
        null
    }

    /** The player's script bundles, as linked from the player's page. */
    fun scripts(html: String): List<String> =
        WEB_PLAYER_SCRIPT.findAll(html).map { it.value }.distinct().toList()

    private val WEB_PLAYER_SCRIPT =
        Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/[A-Za-z0-9._~-]+\.(?:js|mjs)""")

    /**
     * An operation's hash, as the player declares it.
     *
     * The player declares its operations as `"<name>","query","<64 hex>"`.
     */
    fun queryHash(script: String, operation: String): String? =
        OPERATION_DECLARATION.findAll(script)
            .firstOrNull { it.groupValues[1] == operation }
            ?.groupValues
            ?.get(2)

    private val OPERATION_DECLARATION =
        Regex("""["'](\w+)["']\s*,\s*["']query["']\s*,\s*["']([0-9a-f]{64})["']""")

    /**
     * The one-time-password secrets the player carries, newest first.
     *
     * The player holds a short list of them and uses the newest. Each is a plain
     * string — the quotes around it differ from entry to entry, and the string itself
     * can contain either kind of quote, so both are read and the escapes resolved.
     */
    fun secrets(script: String): List<Secret> {
        val found = SECRET_ENTRY.findAll(script).mapNotNull { match ->
            val raw = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val version = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            if (raw.isEmpty()) return@mapNotNull null
            Secret(unescape(raw), version)
        }.toList()
        return found.sortedByDescending { it.version }
    }

    private val SECRET_ENTRY =
        Regex("""secret:\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")\s*,\s*version:\s*(\d+)""")

    private fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                val next = raw[i + 1]
                out.append(
                    when (next) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> next
                    },
                )
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** The operation that asks for a track's canvas. */
    const val CANVAS_OPERATION = "canvas"

    /**
     * The operation that searches for tracks.
     *
     * It was called `searchTracks` and is now `assistedCurationSearch`; the variables
     * went from `searchTerm` to `term` with it.
     */
    const val SEARCH_OPERATION = "assistedCurationSearch"
}
