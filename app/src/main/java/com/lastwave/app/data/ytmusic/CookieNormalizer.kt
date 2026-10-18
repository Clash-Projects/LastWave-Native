package com.lastwave.app.data.ytmusic

import org.json.JSONArray
import org.json.JSONObject

/**
 * Normalizes user-pasted YouTube Music session cookies into a clean `Cookie` header string.
 *
 * Direct port of the desktop app's [InnerTubeMusicApi.normalizeCookies]:
 * Accepts:
 * - Raw Cookie header: `A=1; B=2`
 * - Netscape cookies.txt (TSV format with `#HttpOnly` lines, tab columns)
 * - JSON object or array exported from browser extensions (e.g. Cookie-Editor, EditThisCookie)
 * - Multiline pastes (newlines treated as separators)
 *
 * Validates that at least one SAPISID token (`__Secure-3PAPISID`, `SAPISID`, or `APISID`)
 * is present, which is required to build the `SAPISIDHASH` authorization header.
 */
object CookieNormalizer {

    private val SAPISID_KEYS = listOf("__Secure-3PAPISID", "SAPISID", "APISID")

    /**
     * Normalizes [raw] into a standard `; `-separated cookie header.
     *
     * @throws IllegalArgumentException if input is empty, unparseable, or missing SAPISID tokens.
     */
    fun normalize(raw: String?): String {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) {
            throw IllegalArgumentException("Please enter cookie text.")
        }

        val pairs = mutableMapOf<String, String>()

        fun addPair(name: String, rawVal: String) {
            val n = name.trim()
            var v = rawVal.trim()
            if (n.isEmpty() || v.isEmpty()) return
            // Strip surrounding quotes some exporters add
            if (v.length >= 2 && ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith('\'') && v.endsWith('\'')))) {
                v = v.substring(1, v.length - 1).trim()
            }
            if (v.isNotEmpty()) {
                pairs[n] = v
            }
        }

        // 1. JSON export? e.g. [{"name":"SID","value":"..."}] or {"cookies": [...]} or {"SID":"..."}
        if (input.startsWith("[") || input.startsWith("{")) {
            try {
                if (input.startsWith("[")) {
                    val arr = JSONArray(input)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val n = obj.optString("name")
                        val v = obj.optString("value")
                        addPair(n, v)
                    }
                } else {
                    val obj = JSONObject(input)
                    val cookiesArr = obj.optJSONArray("cookies")
                    if (cookiesArr != null) {
                        for (i in 0 until cookiesArr.length()) {
                            val item = cookiesArr.optJSONObject(i) ?: continue
                            addPair(item.optString("name"), item.optString("value"))
                        }
                    } else {
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (k != "cookies") {
                                addPair(k, obj.optString(k))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Not valid JSON — fall through to line-oriented parsing
            }
        }

        // 2. Line-oriented parse: handles header strings, multiline pastes,
        // Netscape cookies.txt (7 tab columns), and `Cookie: ...` prefixes.
        if (pairs.isEmpty()) {
            for (rawLine in input.lines()) {
                var line = rawLine.trim()
                if (line.isEmpty()) continue

                // `#HttpOnly` lines are DATA in Netscape files, not comments
                if (line.startsWith("#HttpOnly_", ignoreCase = true)) {
                    line = line.substring(10).trim()
                } else if (line.startsWith("#HttpOnly", ignoreCase = true)) {
                    line = line.substring(9).trim()
                } else if (line.startsWith("#")) {
                    continue
                }

                if (line.startsWith("cookie:", ignoreCase = true)) {
                    line = line.substring(7).trim()
                }

                // Netscape format: domain, flag, path, secure, expiry, name, value
                if ('\t' in line) {
                    val cols = line.split('\t')
                    if (cols.size >= 7) {
                        addPair(cols[5], cols[6])
                        continue
                    }
                    line = line.replace('\t', ';')
                }

                // Semicolon-separated header chunks on this line
                for (part in line.split(';')) {
                    val chunk = part.trim()
                    if (chunk.isEmpty() || chunk.startsWith("#")) continue
                    val idx = chunk.indexOf('=')
                    if (idx <= 0) continue
                    addPair(chunk.substring(0, idx), chunk.substring(idx + 1))
                }
            }
        }

        if (pairs.isEmpty()) {
            throw IllegalArgumentException("No cookies found — paste a Cookie header, cookies.txt, or JSON")
        }

        val hasSapisid = SAPISID_KEYS.any { pairs.containsKey(it) }
        if (!hasSapisid) {
            throw IllegalArgumentException(
                "Missing SAPISID — export cookies from music.youtube.com while signed in (need __Secure-3PAPISID or SAPISID)"
            )
        }

        return pairs.keys.sorted().joinToString("; ") { "$it=${pairs[it]}" }
    }
}
