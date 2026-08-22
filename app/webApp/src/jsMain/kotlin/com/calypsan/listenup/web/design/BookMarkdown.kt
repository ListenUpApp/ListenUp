package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Em
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * Renders a book description, which arrives Markdown-flavoured from the metadata source.
 *
 * Every Audible description carries `**bold**`, `_italic_` and blank-line paragraphs, so printing
 * the raw string shows readers literal asterisks on essentially every book in the library. The
 * subset handled here is deliberately the subset that actually appears — emphasis, links and
 * paragraph breaks — rather than a CommonMark dependency for three constructs.
 *
 * ⛔ **The description is untrusted content from an external metadata source**, so this renders by
 * *building DOM nodes*, never by handing an HTML string to the browser. That is a structural
 * guarantee rather than a sanitiser: there is no code path here that can parse a tag, so a
 * `<script>` in a description cannot become one no matter how it is spelled. The two places where
 * attacker-controlled text could still reach an attribute are closed explicitly — a link's scheme
 * is checked, and `rel` is pinned.
 *
 * The semantics deliberately mirror iOS's `AttributedString.fromBookMarkdown`, including its
 * HTML pre-pass, so the same description reads the same on both platforms.
 */
@Composable
internal fun BookMarkdown(description: String) {
    parseBookDescription(description).forEach { paragraph ->
        P {
            paragraph.forEach { span ->
                when (span) {
                    is MarkdownSpan.Plain -> {
                        Text(span.text)
                    }

                    is MarkdownSpan.Strong -> {
                        Strong { Text(span.text) }
                    }

                    is MarkdownSpan.Emphasis -> {
                        Em { Text(span.text) }
                    }

                    is MarkdownSpan.Link -> {
                        A(href = span.href, attrs = {
                            attr("target", "_blank")
                            // The description is someone else's text; a tab it opens must not keep
                            // a handle on this one.
                            attr("rel", "noopener noreferrer")
                        }) { Text(span.text) }
                    }
                }
            }
        }
    }
}

/** One run of description text, already classified — the renderer never re-parses. */
internal sealed interface MarkdownSpan {
    /** Text with no markup left in it. */
    data class Plain(
        val text: String,
    ) : MarkdownSpan

    /** `**strong**`. */
    data class Strong(
        val text: String,
    ) : MarkdownSpan

    /** `*emphasis*` or `_emphasis_`. */
    data class Emphasis(
        val text: String,
    ) : MarkdownSpan

    /** `[label](https://…)`, only ever with a scheme [isSafeHref] allows. */
    data class Link(
        val text: String,
        val href: String,
    ) : MarkdownSpan
}

/** Splits a description into paragraphs of classified spans. Blank input yields no paragraphs. */
internal fun parseBookDescription(description: String): List<List<MarkdownSpan>> =
    normalizeHtml(description)
        .split(PARAGRAPH_BREAK)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { parseSpans(it) }

private fun parseSpans(paragraph: String): List<MarkdownSpan> {
    val spans = mutableListOf<MarkdownSpan>()
    val plain = StringBuilder()
    var index = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            spans += MarkdownSpan.Plain(plain.toString())
            plain.clear()
        }
    }

    while (index < paragraph.length) {
        val match = matchStrong(paragraph, index) ?: matchLink(paragraph, index) ?: matchEmphasis(paragraph, index)
        if (match == null) {
            plain.append(paragraph[index])
            index++
        } else {
            flushPlain()
            spans += match.span
            index = match.end
        }
    }
    flushPlain()
    return spans
}

/** A classified run and where it ends, so the scanner knows where to resume. */
private data class SpanMatch(
    val span: MarkdownSpan,
    val end: Int,
)

private fun matchStrong(
    text: String,
    start: Int,
): SpanMatch? {
    if (!text.startsWith("**", start)) return null
    val close = text.indexOf("**", start + 2)
    if (close <= start + 2) return null
    return SpanMatch(MarkdownSpan.Strong(text.substring(start + 2, close)), close + 2)
}

private fun matchEmphasis(
    text: String,
    start: Int,
): SpanMatch? {
    val marker = text[start]
    if (marker != '*' && marker != '_') return null
    // `snake_case` names appear in paths quoted in descriptions, and `2 * 3` in blurbs. Requiring a
    // word boundary outside the run and non-space just inside it keeps both as written.
    if (marker == '_' && start > 0 && text[start - 1].isLetterOrDigit()) return null
    val close = text.indexOf(marker, start + 1)
    if (close <= start + 1) return null
    if (marker == '_' && close + 1 < text.length && text[close + 1].isLetterOrDigit()) return null
    val content = text.substring(start + 1, close)
    if (content.first().isWhitespace() || content.last().isWhitespace()) return null
    return SpanMatch(MarkdownSpan.Emphasis(content), close + 1)
}

private fun matchLink(
    text: String,
    start: Int,
): SpanMatch? {
    if (text[start] != '[') return null
    val labelEnd = text.indexOf(']', start + 1)
    if (labelEnd < 0 || labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') return null
    val hrefEnd = text.indexOf(')', labelEnd + 2)
    if (hrefEnd < 0) return null
    val label = text.substring(start + 1, labelEnd)
    val href = text.substring(labelEnd + 2, hrefEnd).trim()
    // An unsafe scheme keeps its label and loses its link, rather than the whole run vanishing:
    // the reader still sees what the description said.
    val span = if (isSafeHref(href)) MarkdownSpan.Link(label, href) else MarkdownSpan.Plain(label)
    return SpanMatch(span, hrefEnd + 1)
}

/**
 * Whether a link from a description may become an `href` at all.
 *
 * An allowlist, not a denylist: `javascript:` is the obvious one to keep out, but so is anything
 * else a browser might come to treat as executable, and only http(s) is actually wanted here.
 */
private fun isSafeHref(href: String): Boolean {
    val lower = href.lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
}

/**
 * Converts the HTML that shows up in imported descriptions into Markdown or whitespace, then
 * strips whatever is left so it cannot render as literal angle brackets.
 *
 * The stripping is belt to the renderer's braces — nothing downstream parses HTML — but it is what
 * stops a smuggled `<img …>` from being *shown* to the reader as text.
 */
private fun normalizeHtml(input: String): String {
    if (!input.contains('<')) return input.trim()
    var result = input
    BLOCK_BREAK_TAGS.forEach { tag -> result = result.replace(tag, "\n\n", ignoreCase = true) }
    EMPHASIS_TAGS.forEach { (tag, replacement) -> result = result.replace(tag, replacement, ignoreCase = true) }
    result = result.replace(ANY_TAG, "")
    result = result.replace(EXCESS_BLANK_LINES, "\n\n")
    return result.trim()
}

private val BLOCK_BREAK_TAGS = listOf("<br>", "<br/>", "<br />", "</p>", "</div>")

private val EMPHASIS_TAGS =
    listOf(
        "<i>" to "*",
        "</i>" to "*",
        "<em>" to "*",
        "</em>" to "*",
        "<b>" to "**",
        "</b>" to "**",
        "<strong>" to "**",
        "</strong>" to "**",
    )

private val ANY_TAG = Regex("<[^>]*>")

private val EXCESS_BLANK_LINES = Regex("""\n{3,}""")

/** A blank line — the only thing that starts a new paragraph. A soft wrap does not. */
private val PARAGRAPH_BREAK = Regex("""\n[ \t]*\n""")
