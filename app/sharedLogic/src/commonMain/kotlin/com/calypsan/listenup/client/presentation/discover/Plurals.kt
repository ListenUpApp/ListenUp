package com.calypsan.listenup.client.presentation.discover

/**
 * `"1 book"`, `"3 books"`, `"0 books"` — a count and its noun, agreeing.
 *
 * Regular nouns only, which is every noun these feeds count (book, day, hour, minute, second). A
 * noun needing a real plural form does not belong here; give it its own copy.
 *
 * Exists because three separate call sites had independently written `"$n books"` and shipped
 * "1 books" to the most public screen in the app.
 */
internal fun plural(
    count: Int,
    noun: String,
): String = "$count $noun${if (count == 1) "" else "s"}"
