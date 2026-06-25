package com.calypsan.listenup.server.scanner.pipeline

private val PAREN_ABRIDGED = Regex("""\s*\(abridged\)\s*""", RegexOption.IGNORE_CASE)
private val PAREN_UNABRIDGED = Regex("""\s*\(unabridged\)\s*""", RegexOption.IGNORE_CASE)
private val SUFFIX_UNABRIDGED = Regex("""\s*[-:]\s*unabridged\s*""", RegexOption.IGNORE_CASE)
private val SUFFIX_ABRIDGED = Regex("""\s*[-:]\s*abridged\s*""", RegexOption.IGNORE_CASE)

/**
 * Detects an `(Abridged)`/`(Unabridged)` (or ` - / : abridged`) indicator in [title],
 * strips it, and returns `(cleanedTitle, isAbridged)`. Defaults to unabridged (most
 * audiobooks are). Ports Go's `scanner/analyzer.go parseAbridgedFromTitle`.
 */
fun parseAbridgedFromTitle(title: String): Pair<String, Boolean> {
    val lower = title.lowercase()
    return when {
        "(abridged)" in lower -> PAREN_ABRIDGED.replace(title, " ").trim() to true
        "(unabridged)" in lower -> PAREN_UNABRIDGED.replace(title, " ").trim() to false
        "unabridged" in lower -> SUFFIX_UNABRIDGED.replace(title, " ").trim() to false
        "abridged" in lower -> SUFFIX_ABRIDGED.replace(title, " ").trim() to true
        else -> title to false
    }
}
