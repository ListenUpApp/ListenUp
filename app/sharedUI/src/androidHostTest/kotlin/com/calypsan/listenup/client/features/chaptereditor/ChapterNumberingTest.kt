package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.domain.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chapter numbers under filtering — the trap the list's search field sets.
 *
 * A chapter has no stored number: it is wherever it sits in start-time order, so retiming re-sorts
 * and the number follows for free. That is the right model, and it has exactly one sharp edge —
 * the number is a *position*, and the list does not always show every chapter. Numbering the rows
 * on screen would relabel chapter 213 as chapter 1 the moment someone typed into the search box.
 *
 * [numbered] exists so that cannot happen: number first against the whole book, filter afterwards.
 */
class ChapterNumberingTest {
    private fun book(count: Int): List<Chapter> =
        List(count) { i ->
            Chapter(id = "c$i", title = "Chapter ${i + 1}", duration = 1_000L, startTime = i * 1_000L)
        }

    @Test
    fun `numbering runs from one in start-time order`() {
        val numbered = book(5).numbered()

        assertEquals(listOf(1, 2, 3, 4, 5), numbered.map { it.number })
    }

    @Test
    fun `FILTERING AFTER NUMBERING KEEPS THE TRUE CHAPTER NUMBER`() {
        val numbered = book(311).numbered()

        val visible = numbered.filter { it.chapter.id == "c212" }

        assertEquals(1, visible.size)
        assertEquals(
            "a search that finds one chapter must still call it 213",
            213,
            visible.single().number,
        )
    }

    @Test
    fun `a filtered range keeps its numbers contiguous with the book, not with the view`() {
        val numbered = book(311).numbered()

        val slice = numbered.filter { it.number in 208..217 }

        assertEquals(listOf(208, 209, 210, 211, 212, 213, 214, 215, 216, 217), slice.map { it.number })
    }

    @Test
    fun `numbers follow a retime, because they are positions and nothing is stored`() {
        // Moving the first chapter past the second re-sorts the set; the numbers simply describe
        // the new order. Nothing needed renumbering, which is the whole point of the model.
        val moved =
            book(3)
                .map { if (it.id == "c0") it.copy(startTime = 5_000L) else it }
                .sortedBy { it.startTime }
                .numbered()

        assertEquals(listOf("c1", "c2", "c0"), moved.map { it.chapter.id })
        assertEquals(listOf(1, 2, 3), moved.map { it.number })
    }

    @Test
    fun `an empty book numbers to nothing rather than failing`() {
        assertEquals(emptyList<NumberedChapter>(), emptyList<Chapter>().numbered())
    }
}
