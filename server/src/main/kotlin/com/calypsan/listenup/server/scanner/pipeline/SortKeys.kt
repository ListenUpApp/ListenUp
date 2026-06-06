package com.calypsan.listenup.server.scanner.pipeline

/**
 * Derives sort keys from display strings, preferring an embedded sort tag when
 * present. [titleSort] mirrors Audiobookshelf's leading-article stripping;
 * [sortName] is the inverse of [ContributorParser]'s `Last, First` -> display
 * normalization and produces the contributor identity key.
 */
object SortKeys {
    private val leadingArticle = Regex("""^(the|a|an)\s+""", RegexOption.IGNORE_CASE)

    // CJK Unified Ideographs + Hiragana + Katakana — skip name reordering (matches ContributorParser).
    private val cjk = Regex("""[一-鿿぀-ヿㇰ-ㇿ]""")

    /** Embedded sort title when non-blank, else the title with a leading article stripped. */
    fun titleSort(
        title: String,
        embedded: String?,
    ): String {
        embedded?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return title.replaceFirst(leadingArticle, "").trim().ifEmpty { title.trim() }
    }

    /** Embedded sort name when non-blank, else `"Given Surname"` reordered to `"Surname, Given"`. */
    fun sortName(
        displayName: String,
        embedded: String?,
    ): String {
        embedded?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val name = displayName.trim()
        if (name.contains(',') || cjk.containsMatchIn(name)) return name
        val tokens = name.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return name
        val surname = tokens.last()
        val given = tokens.dropLast(1).joinToString(" ")
        return "$surname, $given"
    }
}
