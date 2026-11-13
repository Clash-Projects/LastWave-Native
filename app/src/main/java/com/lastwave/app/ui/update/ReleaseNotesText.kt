package com.lastwave.app.ui.update

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * GitHub release notes (Markdown) as styled text for the update dialog.
 *
 * Covers what release bodies actually use: `#` headings, `-`/`*`/`+` bullets
 * (nested by indent), `**bold**`, `` `code` ``, `[links](url)` (tappable) and
 * horizontal rules. Images are dropped; anything else stays as plain text, so
 * an unknown construct degrades to readable words rather than disappearing.
 */
internal fun releaseNotesText(markdown: String, linkColor: Color = Color.Unspecified): AnnotatedString =
    buildAnnotatedString {
        val lines = markdown.replace("\r\n", "\n").lines()
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
        var previousBlank = false
        lines.forEachIndexed { index, raw ->
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            // Collapse runs of blank lines into one.
            if (trimmed.isEmpty()) {
                if (!previousBlank && index > 0) append('\n')
                previousBlank = true
                return@forEachIndexed
            }
            if (index > 0 && !previousBlank) append('\n')
            previousBlank = false
            when {
                HORIZONTAL_RULE.matches(trimmed) -> append("―――")
                trimmed.startsWith("#") -> {
                    val level = trimmed.takeWhile { it == '#' }.length
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (level <= 2) 17.sp else 15.sp,
                        ),
                    ) {
                        appendInline(trimmed.drop(level).trim(), linkColor)
                    }
                }
                BULLET.containsMatchIn(trimmed) -> {
                    val depth = (line.length - trimmed.length) / 2
                    append("    ".repeat(depth.coerceAtMost(3)))
                    append("•  ")
                    appendInline(trimmed.drop(2).trim(), linkColor)
                }
                else -> appendInline(trimmed, linkColor)
            }
        }
    }

private fun AnnotatedString.Builder.appendInline(text: String, linkColor: Color) {
    var cursor = 0
    for (match in INLINE.findAll(text)) {
        append(text.substring(cursor, match.range.first))
        val (bold, boldAlt, code, label, url) = match.destructured
        when {
            match.value.startsWith("!") -> Unit
            bold.isNotEmpty() || boldAlt.isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold.ifEmpty { boldAlt }) }
            code.isNotEmpty() ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            label.isNotEmpty() ->
                withLink(
                    LinkAnnotation.Url(
                        url,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ),
                ) { append(label) }
            else -> append(match.value)
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

private val BULLET = Regex("""^[-*+]\s""")
private val HORIZONTAL_RULE = Regex("""^([-*_])\1{2,}$""")
private val INLINE = Regex("""!\[[^\]]*]\([^)]*\)|\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)""")
