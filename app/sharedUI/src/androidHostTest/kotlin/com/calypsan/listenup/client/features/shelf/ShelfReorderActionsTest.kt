package com.calypsan.listenup.client.features.shelf

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Pins the TalkBack/Switch Access path for reordering a shelf. Drag is the only other way, and a
 * screen-reader user cannot drag — so each cell carries "Move earlier" / "Move later" actions that
 * land the book one place over through the same [reorderedBy] the drag uses.
 */
class ShelfReorderActionsTest {
    private val books = listOf("a", "b", "c")

    @Test
    fun `a middle book can move either way`() {
        val actions = shelfReorderActions(books, index = 1, EARLIER, LATER) {}

        actions.map { it.label } shouldBe listOf(EARLIER, LATER)
    }

    @Test
    fun `moving earlier swaps with the book before`() {
        var reordered: List<String>? = null
        val actions = shelfReorderActions(books, index = 1, EARLIER, LATER) { reordered = it }

        actions.single { it.label == EARLIER }.action() shouldBe true

        reordered shouldBe listOf("b", "a", "c")
    }

    @Test
    fun `moving later swaps with the book after`() {
        var reordered: List<String>? = null
        val actions = shelfReorderActions(books, index = 1, EARLIER, LATER) { reordered = it }

        actions.single { it.label == LATER }.action() shouldBe true

        reordered shouldBe listOf("a", "c", "b")
    }

    @Test
    fun `the first book cannot move earlier and the last cannot move later`() {
        shelfReorderActions(books, index = 0, EARLIER, LATER) {}.map { it.label } shouldBe listOf(LATER)
        shelfReorderActions(books, index = 2, EARLIER, LATER) {}.map { it.label } shouldBe listOf(EARLIER)
    }

    @Test
    fun `a lone book has nowhere to go`() {
        shelfReorderActions(listOf("a"), index = 0, EARLIER, LATER) {}.shouldBeEmpty()
    }

    private companion object {
        const val EARLIER = "Move earlier"
        const val LATER = "Move later"
    }
}
