package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The list's search, and the one thing it must never do.
 *
 * `ChapterNumberingTest` already proves [numbered] survives filtering; this proves the filter is
 * actually applied to a numbered list rather than to raw chapters. The two together are what stop
 * a search from relabelling chapter 213 as chapter 1 — the trap the whole numbering model exists
 * to avoid, and one nobody would notice until they saved.
 */
class ChapterListFilterTest {
    private fun book(count: Int): List<NumberedChapter> =
        List(count) { i ->
            Chapter(id = "c$i", title = "Chapter ${i + 1}", duration = 1_000L, startTime = i * 1_000L)
        }.numbered()

    @Test
    fun `a blank query is not a filter`() {
        assertEquals(311, book(311).matching("").size)
        assertEquals(311, book(311).matching("   ").size)
    }

    @Test
    fun `A MATCHED ROW KEEPS ITS TRUE NUMBER`() {
        val found = book(311).matching("Chapter 213")

        assertEquals(1, found.size)
        assertEquals(
            "a search that finds one chapter must still call it 213",
            213,
            found.single().number,
        )
    }

    @Test
    fun `a bare number finds that chapter rather than every title containing the digit`() {
        val found = book(311).matching("213")

        assertEquals(listOf(213), found.map { it.number })
    }

    @Test
    fun `matching is case-insensitive, because nobody capitalises a search`() {
        assertEquals(1, book(20).matching("cHaPtEr 7").size)
    }

    @Test
    fun `a title match and a number match are both kept`() {
        val chapters =
            listOf(
                Chapter(id = "a", title = "The 7 Pillars", duration = 1L, startTime = 0L),
                Chapter(id = "b", title = "Interlude", duration = 1L, startTime = 1L),
                Chapter(id = "c", title = "Nothing relevant", duration = 1L, startTime = 2L),
            ).numbered()

        // "7" matches the first by title; nothing here is numbered 7, so that is the only hit.
        assertEquals(listOf("a"), chapters.matching("7").map { it.chapter.id })
    }

    @Test
    fun `a query that matches nothing returns nothing rather than everything`() {
        assertEquals(emptyList<NumberedChapter>(), book(50).matching("Sanderson"))
    }

    @Test
    fun `surrounding whitespace does not change what matches`() {
        assertEquals(book(50).matching("Chapter 12"), book(50).matching("  Chapter 12  "))
    }
}
