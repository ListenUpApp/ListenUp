package com.calypsan.listenup.client.core

/**
 * One strong run per delimiter: the delimiter, any spaces just inside it, content that starts and ends on a
 * non-space and stays on one line, any spaces before the closing delimiter, and the closing delimiter.
 */
private val STRONG_RUNS =
    listOf("**", "__").map { delimiter ->
        val quoted = Regex.escape(delimiter)
        val first = Regex.escape(delimiter.first().toString())
        Regex("""$quoted([ \t]*)([^\s$first](?:[^\n]*?\S)?)([ \t]*)$quoted""")
    }

/**
 * Moves stray spaces out of `**strong**` and `__strong__` runs, so `**the truth. **` reads as bold.
 *
 * Catalogue descriptions often close a bold run after a space, usually because an HTML `<b>…</b>` around a
 * sentence and its trailing space became Markdown. CommonMark does not count a delimiter that has a space on its
 * inner side, so Android's and Apple's parsers show the asterisks as text. Moving the space outside the delimiter
 * keeps the words and the spacing, and leaves well-formed runs exactly as written. Every client runs a description
 * through this before rendering it, so the same text reads the same on Android, iOS and web.
 */
fun tightenMarkdownEmphasis(markdown: String): String =
    STRONG_RUNS.fold(markdown) { text, run ->
        run.replace(text) { match ->
            val (leading, content, trailing) = match.destructured
            val delimiter = match.value.take(2)
            "$leading$delimiter$content$delimiter$trailing"
        }
    }
