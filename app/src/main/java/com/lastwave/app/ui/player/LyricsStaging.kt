package com.lastwave.app.ui.player

import androidx.compose.runtime.Immutable
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricSyllable
import com.lastwave.app.data.lyrics.VocalRole
import com.lastwave.app.data.lyrics.isRtlText

/**
 * Display model for the synced lyrics list.
 *
 * Provider rows are staged into a flat list of [LyricsItem]s:
 * - [LyricLineGroup]: one lead row plus the backing-vocal rows sung under
 *   it. Backing vocals are nested inside the group (smaller, below the lead)
 *   and never become list items of their own, so a harmony starting never
 *   pulls focus or scroll away from the lead singer.
 * - [LyricInterlude]: an instrumental break of [INTERLUDE_MIN_MS] or more,
 *   drawn as the three-dot countdown.
 *
 * Timing is never changed here: every item starts at a provider timestamp
 * (or at a provider line end for interludes), so focus moves exactly when
 * the source says it does.
 */
@Immutable
internal sealed interface LyricsItem {
    val startMs: Long
    val endMs: Long
    /** Extra breathing room after this item: the next item opens a stanza. */
    val stanzaGapAfter: Boolean
}

/** One timed karaoke run: the lead text of a row, or one backing vocal run. */
@Immutable
internal data class StagedVocal(
    /** Index of the provider row in the source list (translations key on it). */
    val lineIndex: Int,
    val startMs: Long,
    /** Last sung moment, or null when the provider gives no end. */
    val endMs: Long?,
    /** Display text; equals [parts] joined when [units] is not empty. */
    val text: String,
    /** Word timing; empty for line-synced rows. */
    val units: List<LyricSyllable>,
    /** Display string per unit, separators included, same size as [units]. */
    val parts: List<String>,
    val isRtl: Boolean,
) {
    val isWordSynced: Boolean get() = units.isNotEmpty()

    /** [start, end) char range of each unit inside [text]. */
    val charRanges: List<Pair<Int, Int>> by lazy {
        var offset = 0
        parts.map { part ->
            val start = offset
            offset += part.length
            start to offset
        }
    }
}

@Immutable
internal data class LyricLineGroup(
    val lead: StagedVocal?,
    val backgrounds: List<StagedVocal>,
    val role: VocalRole,
    override val startMs: Long,
    override val endMs: Long,
    /** False when no row in the group carries a real end time. */
    val endKnown: Boolean,
    override val stanzaGapAfter: Boolean = false,
) : LyricsItem {
    /** Source row whose translation / romanization is drawn under the group. */
    val subLineIndex: Int get() = lead?.lineIndex ?: backgrounds.first().lineIndex
    val seekMs: Long get() = lead?.startMs ?: startMs
    val isWordSynced: Boolean get() = lead?.isWordSynced == true || backgrounds.any { it.isWordSynced }
}

@Immutable
internal data class LyricInterlude(
    override val startMs: Long,
    override val endMs: Long,
    /** Side the dots sit on: the side of the row that follows the break. */
    val role: VocalRole,
    override val stanzaGapAfter: Boolean = false,
) : LyricsItem

@Immutable
internal class LyricsStage(
    val items: List<LyricsItem>,
    /** Item start times, ascending: binary search finds the active item. */
    val starts: LongArray,
    /** True when the song really has more than one voice side. */
    val isStaged: Boolean,
) {
    fun activeIndex(positionMs: Long): Int {
        var low = 0
        var high = starts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= positionMs) low = mid + 1 else high = mid
        }
        return low - 1
    }

    /** First item start strictly after [positionMs], or null. */
    fun nextStartAfter(positionMs: Long): Long? {
        val i = activeIndex(positionMs) + 1
        return if (i in starts.indices) starts[i] else null
    }

    companion object {
        val Empty = LyricsStage(emptyList(), LongArray(0), false)
    }
}

/** Breaks at least this long get the three-dot countdown. */
internal const val INTERLUDE_MIN_MS = 3_500L

/** A pause this long between rows opens a new stanza. */
private const val STANZA_GAP_MS = 2_500L

/** Same-row start gap that opens a stanza when rows carry no end times. */
private const val STANZA_START_GAP_MS = 6_000L

/** A backing-only row this close after its lead row nests under it. */
private const val BACKING_ATTACH_WINDOW_MS = 3_000L

private fun LyricLine.isMarker(): Boolean {
    val t = text.trim()
    return (t.isEmpty() || t.all { it == '♪' || it == '♫' || it == '…' || it == '.' || it.isWhitespace() }) &&
        syllables.none { s -> s.text.any { it.isLetterOrDigit() } }
}

private fun LyricLine.knownEnd(): Long? {
    val sylEnd = syllables.maxOfOrNull { it.timeMs + it.durationMs }
    val lineEnd = if (durationMs > 0) timeMs + durationMs else null
    return when {
        lineEnd != null && sylEnd != null -> maxOf(lineEnd, sylEnd)
        lineEnd != null -> lineEnd
        sylEnd != null && sylEnd > timeMs -> sylEnd
        else -> null
    }
}

internal fun buildLyricsStage(lines: List<LyricLine>): LyricsStage {
    if (lines.isEmpty()) return LyricsStage.Empty

    data class Draft(
        val lead: StagedVocal?,
        val backgrounds: MutableList<StagedVocal>,
        val role: VocalRole,
        val startMs: Long,
        var endMs: Long?,
        /** A marker row (blank / ♪) sat right before this group. */
        val afterMarkerMs: Long?,
    )

    val drafts = ArrayList<Draft>()
    var pendingMarkerMs: Long? = null

    lines.forEachIndexed { index, line ->
        if (line.isMarker()) {
            // Blank / ♪ rows mark where the previous part stops singing.
            if (pendingMarkerMs == null) pendingMarkerMs = line.timeMs
            drafts.lastOrNull()?.let { prev ->
                val end = prev.endMs
                if (end == null || end > line.timeMs) prev.endMs = maxOf(prev.startMs, line.timeMs)
            }
            return@forEachIndexed
        }
        val leadUnits = line.syllables.filter { !it.isBackground }
        val backUnits = line.syllables.filter { it.isBackground }
        val backingOnly = line.role == VocalRole.BACKGROUND_SUBLINE ||
            (line.hasSyllables && leadUnits.isEmpty())
        val end = line.knownEnd()

        val lead = if (backingOnly) null else stagedVocal(
            lineIndex = index,
            startMs = line.timeMs,
            endMs = end,
            authored = line.text,
            units = leadUnits,
            fallbackText = line.text,
            isBackground = false,
        )
        val backing = if (backUnits.isNotEmpty() || backingOnly) stagedVocal(
            lineIndex = index,
            startMs = backUnits.firstOrNull()?.timeMs ?: line.timeMs,
            endMs = backUnits.maxOfOrNull { it.timeMs + it.durationMs } ?: end,
            // Backing-only rows carry their own authored text; backing runs
            // inside a lead row only have their timed words.
            authored = if (backingOnly) line.text else null,
            units = backUnits,
            fallbackText = if (backingOnly) line.text else backUnits.joinToString(" ") { it.text.trim() },
            isBackground = true,
        ) else null

        val prev = drafts.lastOrNull()
        if (backingOnly && backing != null && prev != null && pendingMarkerMs == null &&
            line.timeMs <= (prev.endMs ?: prev.startMs) + BACKING_ATTACH_WINDOW_MS
        ) {
            prev.backgrounds += backing
            val bgEnd = backing.endMs
            if (bgEnd != null) prev.endMs = maxOf(prev.endMs ?: bgEnd, bgEnd)
            return@forEachIndexed
        }
        if (lead == null && backing == null) return@forEachIndexed
        drafts += Draft(
            lead = lead,
            backgrounds = listOfNotNull(backing).toMutableList(),
            role = if (line.role == VocalRole.BACKGROUND_SUBLINE) prev?.role ?: VocalRole.LEAD_LEFT else line.role,
            startMs = line.timeMs,
            endMs = listOfNotNull(end, backing?.endMs).maxOrNull(),
            afterMarkerMs = pendingMarkerMs,
        )
        pendingMarkerMs = null
    }
    if (drafts.isEmpty()) return LyricsStage.Empty

    val roles = drafts.map { it.role }.toSet()
    val isStaged = roles.size > 1

    val items = ArrayList<LyricsItem>(drafts.size + 8)
    fun interlude(start: Long, end: Long, nextRole: VocalRole) {
        if (end - start >= INTERLUDE_MIN_MS) {
            items += LyricInterlude(startMs = start, endMs = end, role = if (isStaged) nextRole else VocalRole.LEAD_LEFT)
        }
    }

    // Intro: dots count in until the first sung row.
    val first = drafts.first()
    interlude(0L, first.startMs, first.role)

    drafts.forEachIndexed { i, d ->
        val next = drafts.getOrNull(i + 1)
        val end = d.endMs
        var stanzaBreak = false
        var breakStart: Long? = null
        if (next != null) {
            val marker = next.afterMarkerMs
            if (marker != null) {
                stanzaBreak = true
                breakStart = marker
            } else if (end != null) {
                val gap = next.startMs - end
                if (gap >= STANZA_GAP_MS) stanzaBreak = true
                if (gap >= INTERLUDE_MIN_MS) breakStart = end
            } else if (next.startMs - d.startMs >= STANZA_START_GAP_MS) {
                stanzaBreak = true
            }
        }
        items += LyricLineGroup(
            lead = d.lead,
            backgrounds = d.backgrounds.toList(),
            role = d.role,
            startMs = d.startMs,
            endMs = end ?: d.startMs,
            endKnown = end != null,
            stanzaGapAfter = stanzaBreak,
        )
        if (next != null && breakStart != null) {
            interlude(maxOf(breakStart, d.startMs), next.startMs, next.role)
        }
    }

    // Starts must be ascending for the binary search; overlapping duet rows
    // already arrive sorted, interludes sit between their neighbours.
    val starts = LongArray(items.size) { items[it].startMs }
    for (i in 1 until starts.size) if (starts[i] < starts[i - 1]) starts[i] = starts[i - 1]
    return LyricsStage(items, starts, isStaged)
}

private fun stagedVocal(
    lineIndex: Int,
    startMs: Long,
    endMs: Long?,
    authored: String?,
    units: List<LyricSyllable>,
    fallbackText: String,
    isBackground: Boolean,
): StagedVocal? {
    val timed = units.filter { it.text.isNotBlank() }
    if (timed.isEmpty()) {
        var text = fallbackText.replace(WHITESPACE, " ").trim()
        if (isBackground) text = unwrapParens(text)
        if (text.isEmpty()) return null
        return StagedVocal(lineIndex, startMs, endMs, text, emptyList(), emptyList(), isRtlText(text))
    }
    var parts = buildDisplayParts(authored, timed)
    if (isBackground) parts = unwrapParens(parts)
    val text = parts.joinToString("")
    if (text.isBlank()) return null
    return StagedVocal(lineIndex, startMs, endMs, text, timed, parts, isRtlText(text))
}

private val WHITESPACE = Regex("""\s+""")

/** Drops one "( … )" pair only when it wraps the whole text. */
private fun unwrapParens(text: String): String {
    val t = text.trim()
    if (t.length < 3 || t.first() != '(' || t.last() != ')') return t
    var depth = 0
    for (i in t.indices) {
        when (t[i]) {
            '(' -> depth++
            ')' -> depth--
        }
        if (depth == 0 && i < t.lastIndex) return t
    }
    return t.substring(1, t.length - 1).trim()
}

/** Drops one wrapping "( … )" pair from backing-vocal parts, keeping one part per unit. */
private fun unwrapParens(parts: List<String>): List<String> {
    val firstIdx = parts.indexOfFirst { it.isNotBlank() }
    val lastIdx = parts.indexOfLast { it.isNotBlank() }
    if (firstIdx < 0) return parts
    val head = parts[firstIdx].trimStart()
    val tail = parts[lastIdx].trimEnd()
    if (!head.startsWith('(') || !tail.endsWith(')')) return parts
    if (firstIdx == lastIdx && head.length < 2) return parts
    // Only unwrap when the parens pair up around the whole run.
    var depth = 0
    val joined = parts.joinToString("").trim()
    for (i in joined.indices) {
        when (joined[i]) {
            '(' -> depth++
            ')' -> depth--
        }
        if (depth == 0 && i < joined.lastIndex) return parts
    }
    val out = parts.toMutableList()
    if (firstIdx == lastIdx) {
        out[firstIdx] = head.substring(1, head.length - 1).trim()
    } else {
        out[firstIdx] = head.substring(1).trimStart()
        out[lastIdx] = tail.substring(0, tail.length - 1).trimEnd()
    }
    return out
}

private const val CLOSING_PUNCT = ",.!?;:)]}…%”’»、。，！？；：）」』】"
private const val OPENING_PUNCT = "([{“‘«¿¡（「『【"

/** Scripts written without spaces between words. */
private fun isSpaceless(c: Char): Boolean {
    val block = Character.UnicodeBlock.of(c) ?: return false
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
        block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
        block == Character.UnicodeBlock.THAI ||
        block == Character.UnicodeBlock.LAO ||
        block == Character.UnicodeBlock.KHMER ||
        block == Character.UnicodeBlock.MYANMAR
}

private fun heuristicSeparator(prev: String, token: String, unit: LyricSyllable): String {
    if (prev.isEmpty() || unit.appendToPrevious) return ""
    val a = prev.last()
    val b = token.first()
    if (b in CLOSING_PUNCT || a in OPENING_PUNCT) return ""
    if (isSpaceless(a) && isSpaceless(b)) return ""
    return " "
}

/**
 * Display string for each timed unit, separators included, so the drawn text
 * is exactly `parts.joinToString("")` and each unit owns a known char range.
 *
 * With [authored] text the spacing is copied from it: units are located in
 * order, whitespace between them becomes exactly one space, and punctuation
 * the provider did not time glues to its neighbour. Text is only ever taken
 * from between two located units when it holds no letters or digits, so
 * words from another vocal part can never leak in, and runs of whitespace can
 * never become wide gaps. Without authored text (or past a unit that cannot
 * be located) a script-aware rule picks the separator.
 */
internal fun buildDisplayParts(authored: String?, units: List<LyricSyllable>): List<String> {
    if (units.isEmpty()) return emptyList()
    val src = authored?.replace(WHITESPACE, " ")?.trim().orEmpty()
    val parts = ArrayList<String>(units.size)
    var cursor = 0
    var lastContent = ""
    var lastContentIdx = -1
    for (i in units.indices) {
        val token = units[i].text.replace(WHITESPACE, " ").trim()
        if (token.isEmpty()) {
            parts += ""
            continue
        }
        var piece: String? = null
        if (src.isNotEmpty() && cursor <= src.length) {
            val idx = src.indexOf(token, cursor, ignoreCase = true)
            val skipped = if (idx >= 0) src.substring(cursor, idx) else null
            if (skipped != null && skipped.length <= 8 && skipped.none { it.isLetterOrDigit() }) {
                val wsFirst = skipped.indexOfFirst { it == ' ' }
                val display = src.substring(idx, idx + token.length)
                piece = if (wsFirst < 0) {
                    // No space: a fragment of the same word, or punctuation
                    // glued between ("rock'n'roll").
                    if (lastContentIdx >= 0) skipped + display else skipped.trim() + display
                } else {
                    val wsLast = skipped.indexOfLast { it == ' ' }
                    val trailing = skipped.substring(0, wsFirst)
                    val leading = skipped.substring(wsLast + 1)
                    if (trailing.isNotEmpty() && lastContentIdx >= 0) {
                        parts[lastContentIdx] = parts[lastContentIdx] + trailing
                    }
                    (if (lastContentIdx >= 0) " " else "") + leading + display
                }
                cursor = idx + token.length
            } else {
                // Unit not found where expected (provider spelled it
                // differently): skip one authored word to stay aligned.
                val ws = src.indexOf(' ', cursor)
                cursor = if (ws < 0) src.length + 1 else ws + 1
            }
        }
        if (piece == null) {
            piece = heuristicSeparator(lastContent, token, units[i]) + token
        }
        parts += piece
        lastContent = piece
        lastContentIdx = parts.lastIndex
    }
    // Untimed trailing punctuation ("?", "…", ")") stays on the last word.
    if (src.isNotEmpty() && cursor < src.length && lastContentIdx >= 0) {
        val rest = src.substring(cursor)
        if (rest.length <= 8 && rest.none { it.isLetterOrDigit() }) {
            parts[lastContentIdx] = parts[lastContentIdx] + rest.trim()
        }
    }
    return parts
}
