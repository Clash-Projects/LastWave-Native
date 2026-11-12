package com.lastwave.app.data.lyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Shared TTML document parser producing word-timed lyric lines.
 *
 * Handles attribute order variations, nested spans, background vocals and
 * translation tracks, plus the clock formats encountered in the wild
 * (seconds float, mm:ss.mmm, hh:mm:ss.mmm, ms/s suffixes).
 */
object TtmlParser {
    private val SKIPPED_ROLES = setOf("x-translation", "x-roman")
    private const val BACKGROUND_ROLE = "x-bg"
    private val WHITESPACE = Regex("""\s+""")

    fun parse(ttml: String): List<LyricLine> {
        if (ttml.isBlank() || "<p" !in ttml.lowercase()) return emptyList()
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { isExpandEntityReferences = false }
            }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(ttml)))
            // Global clock correction (Metrolist parity): Apple TTML exports
            // carry <head><metadata><audio lyricOffset="…"/> shifting every
            // timestamp. Ignoring it offsets the whole song's sync.
            val offsetMs = readLyricOffset(document)
            val groupAgents = readGroupAgents(document)
            val paragraphs = document.getElementsByTagName("p")
            val sung = ArrayList<LyricLine>(paragraphs.length)
            val agentRoles = LinkedHashMap<String, VocalRole>()
            for (i in 0 until paragraphs.length) {
                val paragraph = paragraphs.item(i) as? Element ?: continue
                val line = lineFrom(paragraph) ?: continue
                val role = agentOf(paragraph)?.let { agent ->
                    agentRoles.getOrPut(agent) {
                        val numeric = agent.removePrefix("v").toIntOrNull()
                        when {
                            agent in groupAgents || (numeric != null && numeric >= 1000) -> VocalRole.UNISON_CENTER
                            // Persons alternate sides in order of appearance.
                            agentRoles.values.count { it != VocalRole.UNISON_CENTER } % 2 == 0 -> VocalRole.LEAD_LEFT
                            else -> VocalRole.DUET_RIGHT
                        }
                    }
                } ?: VocalRole.LEAD_LEFT
                sung += line.copy(role = role).shiftTimes(offsetMs)
            }
            // A single voice (or only a group) is not a duet: keep every row
            // on the lead side instead of centring a whole solo song.
            val staged = if (sung.map { it.role }.toSet().size <= 1) {
                sung.map { if (it.role == VocalRole.LEAD_LEFT) it else it.copy(role = VocalRole.LEAD_LEFT) }
            } else sung
            staged.sortedBy { it.timeMs }
        }.getOrDefault(emptyList())
    }

    fun parseTime(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.endsWith("ms", ignoreCase = true)) return raw.dropLast(2).toDoubleOrNull()?.toLong()
        val stripped = if (raw.endsWith("s", ignoreCase = true) && ":" !in raw) raw.dropLast(1) else raw
        val parts = stripped.split(':')
        val seconds = when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> parts[0].toDoubleOrNull()?.let { m -> parts[1].toDoubleOrNull()?.let { m * 60 + it } }
            3 -> parts[0].toDoubleOrNull()?.let { h ->
                parts[1].toDoubleOrNull()?.let { m ->
                    parts[2].toDoubleOrNull()?.let { h * 3600 + m * 60 + it }
                }
            }
            else -> null
        } ?: return null
        return (seconds * 1000).toLong()
    }

    /** `ttm:agent` declarations whose type is a group / other (choir, both). */
    private fun readGroupAgents(document: org.w3c.dom.Document): Set<String> = runCatching {
        val out = HashSet<String>()
        for (tag in listOf("ttm:agent", "agent")) {
            val nodes = document.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val type = qualified(el, "type").lowercase()
                val id = qualified(el, "xml:id").ifEmpty { qualified(el, "id") }
                if (id.isNotEmpty() && (type == "group" || type == "other")) out += id
            }
        }
        out
    }.getOrDefault(emptySet())

    /** Agent of a row: on the paragraph itself or inherited from its div. */
    private fun agentOf(paragraph: Element): String? {
        var node: Node? = paragraph
        while (node is Element) {
            val agent = qualified(node, "ttm:agent").trim()
            if (agent.isNotEmpty()) return agent.substringBefore(' ')
            node = node.parentNode
        }
        return null
    }

    private fun readLyricOffset(document: org.w3c.dom.Document): Long {
        return runCatching {
            val candidates = ArrayList<Element>()
            for (tag in listOf("audio", "*")) {
                val nodes = document.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as? Element ?: continue
                    if (tag == "*" && el.tagName.substringAfter(':') != "audio") continue
                    candidates += el
                }
                if (candidates.isNotEmpty()) break
            }
            for (el in candidates) {
                val attrs = el.attributes ?: continue
                for (i in 0 until attrs.length) {
                    val attr = attrs.item(i) ?: continue
                    if (attr.nodeName.substringAfter(':') == "lyricOffset") {
                        val seconds = attr.nodeValue?.toDoubleOrNull()
                        if (seconds != null && seconds.isFinite()) {
                            return (seconds * 1000).toLong()
                        }
                    }
                }
            }
            0L
        }.getOrDefault(0L)
    }

    private fun LyricLine.shiftTimes(offsetMs: Long): LyricLine {
        if (offsetMs == 0L) return this
        return copy(
            timeMs = (timeMs + offsetMs).coerceAtLeast(0L),
            syllables = syllables.map { it.copy(timeMs = (it.timeMs + offsetMs).coerceAtLeast(0L)) },
        )
    }

    private fun lineFrom(paragraph: Element): LyricLine? {
        val pieces = mutableListOf<Piece>()
        val backingPieces = mutableListOf<Piece>()
        collect(paragraph, pieces, backingPieces)
        val words = mergeIntoWords(pieces)
        val backingWords = mergeIntoWords(backingPieces)
        val paraBegin = attrTime(paragraph, "begin")
        val paraEnd = attrTime(paragraph, "end")
            ?: paraBegin?.let { attrDuration(paragraph, it) }
        if (words.isEmpty() && backingWords.isEmpty()) {
            val text = leadText(paragraph).replace(WHITESPACE, " ").trim()
            val begin = paraBegin ?: return null
            if (text.isEmpty()) return null
            val duration = if (paraEnd != null && paraEnd > begin) paraEnd - begin else 0L
            return LyricLine(
                timeMs = begin,
                durationMs = duration,
                text = text,
            )
        }
        if (words.isEmpty()) {
            // A row sung only by the backing voices: keep it word-timed and
            // flagged so the view nests it under the lead row before it.
            val first = backingWords.first().timeMs
            val begin = minOf(paraBegin ?: first, first)
            val lastEnd = backingWords.maxOf { it.timeMs + it.durationMs }
            val end = paraEnd?.takeIf { it > begin } ?: lastEnd
            return LyricLine(
                timeMs = begin,
                durationMs = (end - begin).coerceAtLeast(0L),
                text = backingWords.joinToString(" ") { it.text },
                syllables = backingWords.map { it.copy(isBackground = true) },
            )
        }
        val begin = paraBegin ?: words.first().timeMs
        val lineDuration = if (paraEnd != null && paraEnd > begin) paraEnd - begin else {
            val lastEnd = maxOf(
                words.maxOf { it.timeMs + it.durationMs },
                backingWords.maxOfOrNull { it.timeMs + it.durationMs } ?: 0L,
            )
            (lastEnd - begin).coerceAtLeast(0L)
        }
        val syllables = buildList {
            addAll(words)
            backingWords.forEach { add(it.copy(isBackground = true)) }
        }.sortedBy { it.timeMs }
        // Lead text only: background and translation spans never leak into
        // the lead row, and XML indentation never becomes visible spacing.
        val text = LyricsSanitizer.safeLineText(leadText(paragraph), words.map { it.text })
        return LyricLine(
            timeMs = minOf(begin, words.first().timeMs),
            durationMs = lineDuration,
            text = text,
            syllables = syllables,
        )
    }

    /** Text of a paragraph without background / translation / romaji spans. */
    private fun leadText(node: Node): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            val children = n.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child is Element) {
                    val role = qualified(child, "ttm:role")
                    if (role in SKIPPED_ROLES || role == BACKGROUND_ROLE) continue
                    walk(child)
                } else if (child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE) {
                    sb.append(child.textContent.orEmpty())
                }
            }
        }
        walk(node)
        return sb.toString()
    }

    private fun collect(node: Node, out: MutableList<Piece>, backing: MutableList<Piece>) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            when (val child = children.item(i)) {
                is Element -> {
                    val role = qualified(child, "ttm:role")
                    if (role in SKIPPED_ROLES) continue
                    val sink = if (role == BACKGROUND_ROLE) backing else out
                    val begin = attrTime(child, "begin")
                    val end = attrTime(child, "end") ?: (begin?.let { attrDuration(child, it) })
                    if (begin != null && end != null && !hasTimedChild(child)) {
                        sink += Piece.Timed(child.textContent.orEmpty(), begin, end)
                    } else {
                        collect(child, sink, backing)
                    }
                }
                else -> if (child.nodeType == Node.TEXT_NODE) {
                    val text = child.textContent.orEmpty()
                    if (text.isNotEmpty()) out += Piece.Text(text)
                }
            }
        }
    }

    private fun hasTimedChild(element: Element): Boolean {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.getAttribute("begin")?.isNotEmpty() == true || hasTimedChild(child)) return true
        }
        return false
    }

    /**
     * Joins timed pieces into words. Untimed text between spans (commas,
     * quotes, a word the provider did not time) is kept without inventing
     * timing: punctuation glues to its neighbour, and an untimed word becomes
     * a zero-length unit at the start of the next timed word (or at the end
     * of the previous one), so it lights up with real sung timing.
     */
    private fun mergeIntoWords(pieces: List<Piece>): List<LyricSyllable> {
        val words = mutableListOf<LyricSyllable>()
        val current = StringBuilder()
        var start = 0L
        var end = 0L
        var timed = false
        // Untimed words waiting for the next timed word to borrow its start.
        val pendingWords = mutableListOf<String>()
        // Untimed glyphs touching the next timed word, e.g. "(" before "ooh".
        val pendingPrefix = StringBuilder()
        fun flush() {
            val text = current.toString().trim()
            current.setLength(0)
            if (text.isNotEmpty() && timed) {
                words += LyricSyllable(timeMs = start, durationMs = (end - start).coerceAtLeast(0L), text = text)
            }
            timed = false
        }
        fun closePrefix() {
            if (pendingPrefix.isNotEmpty()) {
                pendingWords += pendingPrefix.toString()
                pendingPrefix.setLength(0)
            }
        }
        fun glueToPrevious(text: String) {
            val last = words.lastOrNull() ?: return
            words[words.lastIndex] = last.copy(text = last.text + text)
        }
        fun isPunctuation(token: String) = token.none { it.isLetterOrDigit() }
        pieces.forEach { piece ->
            when (piece) {
                is Piece.Text -> {
                    if (piece.text.isBlank()) {
                        flush()
                        closePrefix()
                        return@forEach
                    }
                    val leadingSpace = piece.text.first().isWhitespace()
                    val trailingSpace = piece.text.last().isWhitespace()
                    if (leadingSpace) {
                        flush()
                        closePrefix()
                    }
                    val tokens = piece.text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
                    tokens.forEachIndexed { index, token ->
                        val touchesLeft = index == 0 && !leadingSpace
                        val touchesRight = index == tokens.lastIndex && !trailingSpace
                        when {
                            // Glued to the timed word being built ("love" + ",").
                            touchesLeft && timed -> current.append(token)
                            touchesLeft && pendingPrefix.isNotEmpty() -> pendingPrefix.append(token)
                            touchesLeft && pendingWords.isEmpty() && words.isNotEmpty() && isPunctuation(token) ->
                                glueToPrevious(token)
                            else -> {
                                if (index > 0 || !touchesLeft) {
                                    flush()
                                    closePrefix()
                                }
                                if (touchesRight) {
                                    pendingPrefix.append(token)
                                } else if (isPunctuation(token) && pendingWords.isEmpty() && words.isNotEmpty() && index == 0) {
                                    glueToPrevious(token)
                                } else {
                                    pendingWords += token
                                }
                            }
                        }
                        if (index < tokens.lastIndex) {
                            flush()
                            closePrefix()
                        }
                    }
                    if (trailingSpace) {
                        flush()
                        closePrefix()
                    }
                }
                is Piece.Timed -> {
                    if (piece.text.isBlank()) return@forEach
                    val spaceBefore = piece.text.first().isWhitespace()
                    if (spaceBefore) {
                        flush()
                        closePrefix()
                    }
                    if (pendingWords.isNotEmpty()) {
                        flush()
                        pendingWords.forEach { w ->
                            words += LyricSyllable(timeMs = piece.start, durationMs = 0L, text = w)
                        }
                        pendingWords.clear()
                    }
                    if (pendingPrefix.isNotEmpty()) {
                        flush()
                        current.append(pendingPrefix)
                        pendingPrefix.setLength(0)
                        start = piece.start
                    } else if (current.isEmpty()) {
                        start = piece.start
                    }
                    current.append(piece.text.trim())
                    end = piece.end
                    timed = true
                    if (piece.text.last().isWhitespace()) flush()
                }
            }
        }
        flush()
        closePrefix()
        // Untimed tail: glue punctuation, keep words at the last word's end.
        val lastWord = words.lastOrNull()
        if (lastWord != null) {
            val tailStart = lastWord.timeMs + lastWord.durationMs
            pendingWords.forEachIndexed { index, w ->
                if (index == 0 && isPunctuation(w)) glueToPrevious(w)
                else words += LyricSyllable(timeMs = tailStart, durationMs = 0L, text = w)
            }
        }
        return words
    }

    private fun attrTime(element: Element, name: String): Long? {
        parseTime(element.getAttribute(name))?.let { return it }
        val local = name.substringAfter(':')
        val attrs = element.attributes ?: return null
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i) ?: continue
            if (attr.nodeName == name || attr.nodeName == local || attr.localName == local) {
                parseTime(attr.nodeValue)?.let { return it }
            }
        }
        return null
    }

    private fun attrDuration(element: Element, begin: Long): Long? {
        val durRaw = element.getAttribute("dur")?.takeIf { it.isNotBlank() }
            ?: run {
                val attrs = element.attributes ?: return null
                var found: String? = null
                for (i in 0 until attrs.length) {
                    val attr = attrs.item(i) ?: continue
                    if (attr.nodeName == "dur" || attr.localName == "dur") {
                        found = attr.nodeValue
                        break
                    }
                }
                found?.takeIf { it.isNotBlank() } ?: return null
            }
        val durMs = parseTime(durRaw) ?: durRaw.trim().toDoubleOrNull()?.let { (it * 1000).toLong() }
        return durMs?.let { begin + it }
    }

    private fun qualified(element: Element, name: String): String {
        element.getAttribute(name)?.takeIf { it.isNotEmpty() }?.let { return it }
        val local = name.substringAfter(':')
        val attrs = element.attributes ?: return ""
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i) ?: continue
            if (attr.nodeName == name || attr.nodeName == local || attr.localName == local) {
                return attr.nodeValue.orEmpty()
            }
        }
        return ""
    }

    private sealed interface Piece {
        data class Text(val text: String) : Piece
        data class Timed(val text: String, val start: Long, val end: Long) : Piece
    }
}
