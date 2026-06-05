package com.calypsan.listenup.server.scanner.pipeline

/**
 * Minimal, dependency-free HTML→Markdown for scanned book descriptions. Audiobook
 * descriptions are simple prose; this handles the tag set Go's `scanner/html.go` guards
 * for (`p br div span b i strong em a ul ol li h1-6 blockquote`) + common entities.
 * Plain-text input (no detected HTML) is returned verbatim. Emphasis tags left unclosed
 * by malformed markup still convert (their span runs to end of input). Any failure falls
 * back to entity-decoded, tag-stripped text — never throws, never returns markup.
 */
object HtmlToMarkdown {
    private val HTML_TAG =
        Regex(
            """<(p|br|div|span|b|i|strong|em|a|ul|ol|li|h[1-6]|blockquote)[\s>/]""",
            RegexOption.IGNORE_CASE,
        )

    private val DOTALL_IGNORE_CASE = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private const val BLANK_LINE = "\n\n"

    private val ANCHOR =
        Regex("""<a\b[^>]*\bhref=["']([^"']*)["'][^>]*>(.*?)</a>""", DOTALL_IGNORE_CASE)
    private val BOLD =
        Regex("""<(?:b|strong)\b[^>]*>(.*?)(?:</(?:b|strong)>|$)""", DOTALL_IGNORE_CASE)
    private val ITALIC =
        Regex("""<(?:i|em)\b[^>]*>(.*?)(?:</(?:i|em)>|$)""", DOTALL_IGNORE_CASE)
    private val LIST_ITEM =
        Regex("""<li\b[^>]*>(.*?)(?:</li>|$)""", DOTALL_IGNORE_CASE)
    private val BLOCKQUOTE =
        Regex("""<blockquote\b[^>]*>(.*?)</blockquote>""", DOTALL_IGNORE_CASE)
    private val LINE_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val PARAGRAPH_END = Regex("""</p\s*>""", RegexOption.IGNORE_CASE)
    private val REMAINING_TAGS = Regex("""<[^>]+>""")
    private val EXCESS_BLANK_LINES = Regex("""\n{3,}""")

    fun convert(input: String): String {
        if (input.isBlank() || !HTML_TAG.containsMatchIn(input)) return input
        return try {
            convertHtml(input)
        } catch (_: Exception) {
            stripTags(decodeEntities(input)).collapse()
        }
    }

    private fun convertHtml(html: String): String {
        var text = ANCHOR.replace(html) { "[${it.groupValues[2]}](${it.groupValues[1]})" }
        text = replaceHeadings(text)
        text = BOLD.replace(text) { "**${it.groupValues[1]}**" }
        text = ITALIC.replace(text) { "_${it.groupValues[1]}_" }
        text = LIST_ITEM.replace(text) { "\n- ${it.groupValues[1].trim()}" }
        text = BLOCKQUOTE.replace(text) { "\n> ${it.groupValues[1].trim()}\n" }
        text = LINE_BREAK.replace(text, "\n")
        text = PARAGRAPH_END.replace(text, "\n\n")
        return stripTags(decodeEntities(text)).collapse()
    }

    private fun replaceHeadings(text: String): String {
        var result = text
        for (level in 1..6) {
            val heading = Regex("""<h$level\b[^>]*>(.*?)</h$level>""", DOTALL_IGNORE_CASE)
            result =
                heading.replace(result) {
                    val title = it.groupValues[1].trim()
                    BLANK_LINE + "#".repeat(level) + " " + title + BLANK_LINE
                }
        }
        return result
    }

    private fun stripTags(s: String): String = REMAINING_TAGS.replace(s, "")

    private fun decodeEntities(s: String): String =
        s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")

    private fun String.collapse(): String =
        lineSequence()
            .joinToString("\n") { it.trimEnd() }
            .let { EXCESS_BLANK_LINES.replace(it, "\n\n") }
            .trim()
}
