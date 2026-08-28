package com.calypsan.listenup.client.features.chaptereditor

/**
 * Narrows the list to what the user typed, without ever renumbering it.
 *
 * Deliberately takes an already-[numbered] list rather than raw chapters. A chapter has no stored
 * number — it is wherever it sits in start-time order — so numbering the *visible* rows would
 * relabel chapter 213 as chapter 1 the moment someone typed. Numbering first and filtering second
 * makes that impossible rather than merely avoided, which is what [numbered] exists for.
 *
 * Matches a title substring, case-insensitively, or the chapter's own number typed exactly. The
 * number is the reason this feature is worth having on a 311-chapter book: "213" should find
 * chapter 213, not the two hundred rows whose titles happen to contain a 2.
 *
 * A blank query is not a filter — it returns everything rather than nothing.
 */
internal fun List<NumberedChapter>.matching(query: String): List<NumberedChapter> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this

    val asNumber = trimmed.toIntOrNull()
    return filter { numbered ->
        numbered.chapter.title.contains(trimmed, ignoreCase = true) || numbered.number == asNumber
    }
}
