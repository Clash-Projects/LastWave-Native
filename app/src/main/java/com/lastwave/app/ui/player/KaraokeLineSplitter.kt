package com.lastwave.app.ui.player

import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricSyllable

/**
 * Display text for each syllable: the syllable text plus the karaoke
 * separator space the canvas renderer draws after it.
 *
 * This is the single source of truth for the separator rule — it mirrors
 * the renderer mapping's inline rule exactly (word spacing is
 * display-only: no timing change, skipped for spaceless CJK lines, for
 * providers that already kept spacing, and before Apple Music `part`
 * continuations like "with"+"drawals"). Both the renderer mapping and the
 * [splitKaraokeToFit] word grouping below must agree on where words end,
 * otherwise a "word" here could straddle a wrap point the canvas cannot
 * break at (or vice versa).
 */
fun renderedSyllableContents(units: List<LyricSyllable>, needsSpacing: Boolean): List<String> {
    return units.mapIndexed { index, syl ->
        val next = units.getOrNull(index + 1)
        val separator = if (needsSpacing &&
            index < units.lastIndex &&
            !syl.text.endsWith(' ') &&
            !syl.text.endsWith('\u00A0') &&
            next?.appendToPrevious != true &&
            (next == null || (!next.text.startsWith(' ') && !next.text.startsWith('\u00A0')))
        ) " " else ""
        syl.text + separator
    }
}

/**
 * Balanced word-wrap packing (Knuth-Plass-lite): partitions [widths] into
 * the fewest rows that each fit [maxWidth], minimizing the sum of squared
 * leftover space so rows come out even ("Where two and two alone" /
 * "will never meet") instead of ragged ("Where two and two alone will" /
 * "meet"). Mirrors the cost function of the karaoke canvas's own balancer.
 *
 * Units wider than [maxWidth] can never share a row — the greedy fallback
 * isolates each on its own row rather than failing outright.
 */
fun packBalanced(widths: List<Float>, maxWidth: Float): List<IntRange> {
    if (widths.isEmpty()) return emptyList()
    val n = widths.size
    val costs = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
    val breaks = IntArray(n + 1)
    costs[0] = 0.0
    for (i in 1..n) {
        var rowWidth = 0f
        for (j in i downTo 1) {
            rowWidth += widths[j - 1]
            if (rowWidth > maxWidth) break
            val badness = (maxWidth - rowWidth).toDouble().let { it * it }
            if (costs[j - 1] + badness < costs[i]) {
                costs[i] = costs[j - 1] + badness
                breaks[i] = j - 1
            }
        }
    }
    if (costs[n].isInfinite()) {
        val rows = mutableListOf<IntRange>()
        var start = 0
        var rowWidth = 0f
        widths.forEachIndexed { index, unitWidth ->
            if (rowWidth > 0f && rowWidth + unitWidth > maxWidth) {
                rows.add(start..index - 1)
                start = index
                rowWidth = 0f
            }
            rowWidth += unitWidth
        }
        rows.add(start..n - 1)
        return rows
    }
    val rows = mutableListOf<IntRange>()
    var index = n
    while (index > 0) {
        val start = breaks[index]
        rows.add(0, start..index - 1)
        index = start
    }
    return rows
}

/**
 * Splits an overlong word-sync line into balanced, independently-timed
 * sub-lines that each fit [maxWidthPx] when drawn with the karaoke style.
 *
 * Why this exists: the karaoke canvas pre-computes its row wrapping once per
 * line (unstated `remember` with no width key), so a line measured against a
 * transient width — first composition during the panel crossfade, an async
 * layout-cache fill, a provider upgrade reusing an item slot — can freeze as
 * one giant row that runs past the screen edge (Apple/Paxsenix word-sync
 * lines are the usual victims: long enough to overflow, short enough to
 * look like they should fit). Short sub-lines render as identical single
 * rows under ANY measured width, so the symptom is impossible by
 * construction, for every provider.
 *
 * Timing stays exact: chunks partition the syllable sequence in order, each
 * chunk's range comes from its own first/last syllable timestamps, and
 * background syllables ride with the chunk containing their start, so
 * karaoke fill, line focus and auto-scroll behave as before — just on
 * shorter rows. Returns `listOf(this)` untouched when the line already fits
 * or cannot be split (no syllables, single unbreakable word).
 */
fun LyricLine.splitKaraokeToFit(
    maxWidthPx: Float,
    measureWidth: (String) -> Float,
): List<LyricLine> {
    if (!hasSyllables || maxWidthPx <= 0f) return listOf(this)
    val leads = syllables.filter { !it.isBackground }
    val units = leads.ifEmpty { syllables }
    val backs = if (leads.isNotEmpty()) syllables.filter { it.isBackground } else emptyList()
    val needsSpacing = text.contains(' ') || text.contains('\u00A0')
    val rendered = renderedSyllableContents(units, needsSpacing)

    // Word grouping mirrors the canvas renderer's own grouping (a syllable
    // whose drawn text ends in whitespace ends the word), so every chunk
    // boundary below is a point the canvas can also break at.
    val words = mutableListOf<MutableList<Int>>()
    var current = mutableListOf<Int>()
    rendered.forEachIndexed { index, content ->
        current.add(index)
        if (content.trimEnd().length < content.length) {
            words.add(current)
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) words.add(current)

    val wordTexts = words.map { idxs -> idxs.joinToString("") { rendered[it] } }
    val wordWidths = wordTexts.map(measureWidth)
    if (wordWidths.sum() <= maxWidthPx) return listOf(this)

    // Over-wide words (long compounds) cannot share any row — explode them
    // into syllable units first so the packer always has a feasible layout.
    val packUnits = mutableListOf<List<Int>>()
    val packWidths = mutableListOf<Float>()
    words.forEachIndexed { wordIndex, idxs ->
        if (wordWidths[wordIndex] <= maxWidthPx) {
            packUnits.add(idxs)
            packWidths.add(wordWidths[wordIndex])
        } else {
            idxs.forEach { unitIndex ->
                packUnits.add(listOf(unitIndex))
                packWidths.add(measureWidth(rendered[unitIndex]))
            }
        }
    }
    val rows = packBalanced(packWidths, maxWidthPx)
    if (rows.size <= 1) return listOf(this)

    val chunkUnitIdxs = rows.map { row -> row.flatMap { packUnits[it] } }
    val chunkRanges = chunkUnitIdxs.map { idxs ->
        val chunkLeads = idxs.map { units[it] }
        val start = chunkLeads.minOf { it.timeMs }
        val end = chunkLeads.maxOf { it.timeMs + it.durationMs }
        start to end
    }
    val backsByChunk = List(chunkUnitIdxs.size) { mutableListOf<LyricSyllable>() }
    backs.forEach { back ->
        val target = chunkRanges.indexOfFirst { back.timeMs >= it.first && back.timeMs < it.second }
            .takeIf { it >= 0 }
            ?: if (back.timeMs < chunkRanges.first().first) 0 else chunkRanges.lastIndex
        backsByChunk[target].add(back)
    }

    val originalEnd = timeMs + durationMs
    return chunkUnitIdxs.mapIndexed { chunkIndex, idxs ->
        val chunkLeads = idxs.map { units[it] }
        val ownedBacks = backsByChunk[chunkIndex]
        val containedStart = minOf(chunkLeads.minOf { it.timeMs }, ownedBacks.minOfOrNull { it.timeMs } ?: Long.MAX_VALUE)
        val containedEnd = maxOf(
            chunkLeads.maxOf { it.timeMs + it.durationMs },
            ownedBacks.maxOfOrNull { it.timeMs + it.durationMs } ?: Long.MIN_VALUE,
        )
        // Preserve the original line's full coverage: first chunk opens no
        // later than the line did, chunks join contiguously, last chunk
        // closes no earlier than the line did — focus never gaps or clips.
        val nextStart = chunkUnitIdxs.getOrNull(chunkIndex + 1)
            ?.let { nextIdxs -> nextIdxs.map { units[it] }.minOf { it.timeMs } }
        val start = if (chunkIndex == 0) minOf(containedStart, timeMs) else containedStart
        val end = when {
            nextStart != null -> maxOf(containedEnd, nextStart)
            else -> maxOf(containedEnd, originalEnd)
        }
        LyricLine(
            timeMs = start,
            durationMs = (end - start).coerceAtLeast(0L),
            text = idxs.joinToString("") { rendered[it] }.trimEnd(),
            syllables = (chunkLeads + ownedBacks).sortedBy { it.timeMs },
            transliteration = if (chunkIndex == 0) transliteration else null,
        )
    }
}

/**
 * Word-merge normalization (Metrolist rule): providers emit fragments at
 * wildly different granularities — Apple/Paxsenix words, BetterLyrics
 * spans, Kugou KRC characters, lone punctuation tokens — and every
 * renderer inserts a display space after each syllable. Without merging,
 * `hello`+`,` renders as `hello ,` and Kugou `Hel`+`lo` as `Hel lo`.
 *
 * The authored line text is the ground truth: fragments whose characters
 * (whitespace stripped) equal the line's characters are consumed per word
 * length, so Kugou `Hel`+`lo` becomes `Hello` and lone `,` glues to its
 * neighbor (`, world` stays far, `hello,` stays close); hyphen compounds
 * stay whole. Spaceless (CJK) rows are untouched so per-character timing
 * survives. Leads and background vocals normalize separately and rejoin
 * by time. Idempotent: merged words re-align to themselves.
 */
fun normalizeWordSpacing(lines: List<LyricLine>): List<LyricLine> {
    if (lines.isEmpty()) return lines
    return lines.map { it.normalizedSpacing() }
}

private fun LyricLine.normalizedSpacing(): LyricLine {
    if (!hasSyllables || syllables.size < 2) return this
    // Spaceless scripts (CJK/Thai): spaces would destroy per-char timing.
    if (!text.contains(' ') && !text.contains('\u00A0')) return this
    val leads = syllables.filter { !it.isBackground }
    val backs = syllables.filter { it.isBackground }
    val normLeads = mergeFragmentsIntoWords(text, leads)
    val normBacks = mergeFragmentsIntoWords(text, backs)
    val visible = if (leads.isNotEmpty()) normLeads else normBacks
    if (visible.isEmpty()) return this
    val newText = visible.joinToString(" ") { it.text }
    if (newText == text && normLeads.map { it.text } == leads.map { it.text } &&
        normBacks.map { it.text } == backs.map { it.text }
    ) {
        return this
    }
    return copy(text = newText, syllables = (normLeads + normBacks).sortedBy { it.timeMs })
}

/**
 * Reconciles provider fragments with the authored line text. Providers emit
 * at wildly different granularities — whole words (Apple, BetterLyrics
 * spans), characters (Kugou KRC), lone punctuation — so fragment count
 * alone says nothing. The authored [lineText] is the ground truth: when the
 * fragments' characters (whitespace stripped) equal the line's characters,
 * fragments are consumed per word length; otherwise each fragment is
 * already a word. Either way punctuation glues to its neighbor last.
 */
private fun mergeFragmentsIntoWords(lineText: String, units: List<LyricSyllable>): List<LyricSyllable> {
    if (units.isEmpty()) return units
    // Phase A: explicit continuations (Apple `part` like "with"+"drawals")
    // glue to the previous token regardless of spacing.
    val glued = mutableListOf<LyricSyllable>()
    for (syl in units) {
        val last = glued.lastOrNull()
        if (syl.appendToPrevious && last != null) {
            glued[glued.lastIndex] = last.copy(
                text = last.text + syl.text.trimStart(),
                durationMs = maxOf(last.timeMs + last.durationMs, syl.timeMs + syl.durationMs) - last.timeMs,
            )
        } else {
            glued += syl
        }
    }
    val targetWords = lineText.split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (targetWords.isEmpty()) return glued
    val aligned = alignFragmentsToWords(targetWords, glued) ?: glued.map { piece ->
        // Fallback: each fragment is already a word; trim only.
        val text = piece.text.trim()
        if (text == piece.text) piece else piece.copy(text = text)
    }.filter { it.text.isNotEmpty() }
    return gluePunctuation(aligned)
}

private fun alignFragmentsToWords(
    targetWords: List<String>,
    pieces: List<LyricSyllable>,
): List<LyricSyllable>? {
    val strippedTargets = targetWords.map { it.filterNot { c -> c.isWhitespace() } }
    if (strippedTargets.any { it.isEmpty() }) return null
    // A piece holding several words ("New York" as one span) splits first,
    // slicing its timing proportionally across the words it covers.
    val atoms = mutableListOf<LyricSyllable>()
    for (piece in pieces) {
        val parts = piece.text.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (parts.size <= 1) {
            atoms += piece
        } else {
            val span = (piece.timeMs + piece.durationMs - piece.timeMs).coerceAtLeast(0L)
            val totalChars = parts.sumOf { it.length }.coerceAtLeast(1)
            var cursor = piece.timeMs
            parts.forEachIndexed { index, part ->
                val share = ((span * part.length) / totalChars).coerceAtLeast(0L)
                val start = cursor
                val end = if (index == parts.lastIndex) piece.timeMs + piece.durationMs else cursor + share
                atoms += piece.copy(text = part, timeMs = start, durationMs = (end - start).coerceAtLeast(0L))
                cursor = end
            }
        }
    }
    val strippedAtoms = atoms.map { it.text.filterNot { c -> c.isWhitespace() } }
    if (strippedAtoms.any { it.isEmpty() }) return null
    if (strippedAtoms.sumOf { it.length } != strippedTargets.sumOf { it.length }) return null
    if (strippedAtoms.joinToString("") != strippedTargets.joinToString("")) return null
    // Same character stream: consume atoms per target word length.
    val out = mutableListOf<LyricSyllable>()
    var atomIndex = 0
    var charOffset = 0
    for (target in strippedTargets) {
        var remaining = target.length
        val group = mutableListOf<LyricSyllable>()
        while (remaining > 0 && atomIndex < atoms.size) {
            val atom = atoms[atomIndex]
            val atomText = strippedAtoms[atomIndex]
            val take = minOf(remaining, atomText.length - charOffset)
            if (take <= 0) {
                atomIndex++
                charOffset = 0
                continue
            }
            group += atom
            charOffset += take
            remaining -= take
            if (charOffset >= atomText.length) {
                atomIndex++
                charOffset = 0
            }
        }
        if (group.isEmpty()) return null
        val start = group.minOf { it.timeMs }
        val end = group.maxOf { it.timeMs + it.durationMs }
        out += LyricSyllable(
            timeMs = start,
            durationMs = (end - start).coerceAtLeast(0L),
            text = target,
            isBackground = group.first().isBackground,
            appendToPrevious = group.first().appendToPrevious,
        )
    }
    return out
}

/** Punctuation that must sit close to the previous token (`, world`→ far side only). */
private val NoSpaceBefore = setOf(
    ',', '.', '!', '?', ';', ':', '%', '‰', '…', '›', '»', ')', ']', '}', '’', '”', '"', '\'',
    '、', '，', '。', '！', '？', '；', '：', '）', '】', '〉', '-', '–', '—', '/', '&', '~',
)

/** Punctuation that must sit close to the next token (`(hello`, `mother-`). */
private val NoSpaceAfter = setOf(
    '(', '[', '{', '«', '‹', '“', '‘', '"', '\'', '#', '$', '-', '–', '—', '/', '&', '@',
)

private fun gluePunctuation(words: List<LyricSyllable>): List<LyricSyllable> {
    if (words.size < 2) return words
    val out = mutableListOf<LyricSyllable>()
    for (word in words) {
        val last = out.lastOrNull()
        val firstChar = word.text.firstOrNull()
        val lastEnd = last?.text?.lastOrNull()
        if (last != null && firstChar != null &&
            ((lastEnd != null && lastEnd in NoSpaceAfter) || firstChar in NoSpaceBefore)
        ) {
            out[out.lastIndex] = last.copy(
                text = last.text + word.text,
                durationMs = maxOf(last.timeMs + last.durationMs, word.timeMs + word.durationMs) - last.timeMs,
            )
        } else {
            out += word
        }
    }
    return out
}

/**
 * Gives every line-sync row (no syllables) an explicit duration reaching the
 * next row's start, so downstream wrapping and focus math never divide an
 * unknown span. Word-sync rows are untouched — their timing is exact.
 */
fun backfillLineSyncDurations(lines: List<LyricLine>): List<LyricLine> {
    if (lines.isEmpty()) return lines
    return lines.mapIndexed { i, line ->
        if (line.durationMs > 0 || line.syllables.isNotEmpty()) line
        else if (i < lines.lastIndex) {
            val nextStart = lines[i + 1].timeMs
            if (nextStart > line.timeMs) {
                val gap = nextStart - line.timeMs
                line.copy(durationMs = if (gap <= 6000L) gap else 4500L)
            } else line
        } else line.copy(durationMs = 4500L)
    }
}

/**
 * Balanced pre-split for line-sync rows, mirroring [splitKaraokeToFit]: an
 * overlong row is partitioned into word-balanced chunks that each fit
 * [maxWidthPx], so the karaoke canvas can never freeze a giant row
 * off-screen. Spaceless (CJK) rows split by character.
 *
 * Timing stays contiguous: the row's own [timeMs, timeMs + durationMs] span
 * is sliced proportionally to measured chunk width, so focus sweeps the
 * chunks in order with no gaps. Returns `listOf(this)` untouched when the
 * row already fits, has no splittable units, or carries no duration to
 * slice (call [backfillLineSyncDurations] first).
 */
fun LyricLine.splitLineSyncToFit(
    maxWidthPx: Float,
    measureWidth: (String) -> Float,
): List<LyricLine> {
    if (hasSyllables || maxWidthPx <= 0f) return listOf(this)
    if (text.isBlank()) return listOf(this)
    val spanMs = durationMs
    if (spanMs <= 0L) return listOf(this)
    val spaced = text.contains(' ') || text.contains('\u00A0')
    val units: List<String> = if (spaced) {
        text.split(Regex("""\s+""")).filter { it.isNotEmpty() }
    } else {
        text.map { it.toString() }
    }
    if (units.size <= 1) return listOf(this)
    // Trailing-space measuring mirrors the canvas: separators are
    // display-only and the last unit of a row carries none.
    val unitWidths = if (spaced) units.map { measureWidth("$it ") } else units.map(measureWidth)
    if (unitWidths.sum() <= maxWidthPx) return listOf(this)
    val rows = packBalanced(unitWidths, maxWidthPx)
    if (rows.size <= 1) return listOf(this)

    val endMs = timeMs + spanMs
    val totalW = unitWidths.sum().takeIf { it > 0f }
    val boundaries = mutableListOf(timeMs)
    var accW = 0f
    rows.dropLast(1).forEach { row ->
        row.forEach { accW += unitWidths[it] }
        val cut = if (totalW != null) {
            timeMs + ((spanMs * (accW / totalW)).toLong()).coerceIn(0L, spanMs)
        } else {
            timeMs + (spanMs * (boundaries.size) / rows.size)
        }
        // Boundaries must advance strictly so no chunk is empty.
        boundaries.add(maxOf(cut, boundaries.last() + 1))
    }
    boundaries.add(endMs)

    return rows.mapIndexed { chunkIndex, row ->
        val start = boundaries[chunkIndex].coerceAtMost(endMs)
        val end = boundaries[chunkIndex + 1].coerceAtLeast(start)
        LyricLine(
            timeMs = start,
            durationMs = (end - start).coerceAtLeast(0L),
            text = if (spaced) {
                row.joinToString(" ") { units[it] }
            } else {
                row.joinToString("") { units[it] }
            },
            transliteration = if (chunkIndex == 0) transliteration else null,
        )
    }
}
