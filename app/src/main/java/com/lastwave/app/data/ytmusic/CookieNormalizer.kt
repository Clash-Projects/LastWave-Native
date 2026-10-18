package com.lastwave.app.data.ytmusic

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
        // Uses pure Kotlin regex parsing so unit tests on the JVM don't fail due to Android SDK org.json stubs.
        if (input.startsWith("[") || input.startsWith("{")) {
            val objPattern = Regex("""\{[^{}]*\}""")
            val namePattern = Regex(""""name"\s*:\s*"([^"]+)"""")
            val valuePattern = Regex(""""value"\s*:\s*"([^"]*)"""")
            for (match in objPattern.findAll(input)) {
                val block = match.value
                val n = namePattern.find(block)?.groupValues?.getOrNull(1)
                val v = valuePattern.find(block)?.groupValues?.getOrNull(1)
                if (n != null && v != null) {
                    addPair(n, v)
                }
            }
            if (pairs.isEmpty()) {
                val kvPattern = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")
                val ignoredKeys = setOf("name", "value", "cookies", "domain", "path", "expirationDate", "hostOnly", "httpOnly", "sameSite", "secure", "session", "storeId")
                for (m in kvPattern.findAll(input)) {
                    val k = m.groupValues[1]
                    val v = m.groupValues[2]
                    if (k !in ignoredKeys) {
                        addPair(k, v)
                    }
                }
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
