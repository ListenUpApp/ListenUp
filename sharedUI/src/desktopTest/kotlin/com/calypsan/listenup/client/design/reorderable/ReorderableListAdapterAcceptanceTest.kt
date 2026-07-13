package com.calypsan.listenup.client.design.reorderable

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Acceptance tests proving [ReorderNegotiator]/[ReorderMove] generalize to the two shapes this
 * primitive exists to serve — without either shape leaking a domain type into `design.reorderable`.
 * Both adapters below are intentionally local to this test: the real ones live in the consuming
 * plans (Reading Orders' own screen plan; Plan D's chapter Structure lens).
 */
class ReorderableListAdapterAcceptanceTest :
    FunSpec({
        // ── Flat adapter: ReorderMove -> the full List<Id> ordering ReadingOrderRepository
        //    .reorderBooks(id: ReadingOrderId, orderedBookIds: List<BookId>) expects. ────────────

        /** Test-only stand-in for the id type `reorderBooks` actually takes (`BookId` in prod). */
        data class FakeBookId(val value: String)

        /**
         * Applies a [ReorderMove] from a flat [ReorderableList] (every [ReorderNode.parentId] is
         * null) to a `List<FakeBookId>`, producing the full post-move ordering — the exact shape
         * `onReorder(List<Id>)` / `ReadingOrderRepository.reorderBooks` needs. Mirrors what a real
         * Reading-Orders screen's `onMove` callback will do.
         */
        fun applyFlatMove(
            current: List<FakeBookId>,
            move: ReorderMove,
        ): List<FakeBookId> {
            val mutable = current.toMutableList()
            val movedIndex = mutable.indexOfFirst { it.value == move.movedId }
            val moved = mutable.removeAt(movedIndex)
            mutable.add(move.newIndex, moved)
            return mutable
        }

        test("a flat ReorderMove feeds a reorderBooks-shaped List<Id> in the right order") {
            val books = listOf(FakeBookId("book-1"), FakeBookId("book-2"), FakeBookId("book-3"))
            val nodes = books.map { ReorderNode(id = it.value, parentId = null, canHaveChildren = false) }

            val move = ReorderNegotiator.resolveMove(nodes, draggedId = "book-1", newParentId = null, targetIndex = 2)
            checkNotNull(move)

            applyFlatMove(books, move) shouldBe listOf(FakeBookId("book-2"), FakeBookId("book-3"), FakeBookId("book-1"))
        }

        // ── Nested adapter: a reparent ReorderMove -> a changed grouping header. ─────────────────

        /** Test-only stand-in for the chapter shape a real adapter maps (`Chapter.partTitle` in prod). */
        data class FakeChapter(val id: String, val title: String, var partTitle: String?)

        /**
         * Applies a reparent [ReorderMove] from a nested [ReorderableList] (chapters grouped under
         * "Part" header nodes) to a `List<FakeChapter>`, updating the moved chapter's grouping
         * field — the shape Plan D's chapter Structure lens applies to real `Chapter.partTitle`.
         * A move to `newParentId = null` clears the grouping (chapter becomes ungrouped).
         */
        fun applyReparentMove(
            chapters: List<FakeChapter>,
            move: ReorderMove,
            partTitleFor: (parentId: String?) -> String?,
        ): List<FakeChapter> =
            chapters.map { chapter ->
                if (chapter.id == move.movedId) {
                    chapter.copy(partTitle = partTitleFor(move.newParentId))
                } else {
                    chapter
                }
            }

        test("a reparent ReorderMove changes the moved chapter's grouping header") {
            val nodes =
                listOf(
                    ReorderNode(id = "part-1", parentId = null, canHaveChildren = true),
                    ReorderNode(id = "ch-a", parentId = "part-1", canHaveChildren = false),
                    ReorderNode(id = "part-2", parentId = null, canHaveChildren = true),
                    ReorderNode(id = "ch-b", parentId = "part-2", canHaveChildren = false),
                )
            val chapters =
                listOf(
                    FakeChapter(id = "ch-a", title = "Chapter A", partTitle = "Part One"),
                    FakeChapter(id = "ch-b", title = "Chapter B", partTitle = "Part Two"),
                )
            val partTitles = mapOf("part-1" to "Part One", "part-2" to "Part Two")

            val move = ReorderNegotiator.resolveMove(nodes, draggedId = "ch-a", newParentId = "part-2", targetIndex = 1)
            checkNotNull(move)

            val updated = applyReparentMove(chapters, move) { parentId -> partTitles[parentId] }
            updated.first { it.id == "ch-a" }.partTitle shouldBe "Part Two"
            updated.first { it.id == "ch-b" }.partTitle shouldBe "Part Two" // unaffected sibling
        }

        test("a move to root (newParentId = null) clears the chapter's grouping header") {
            val nodes =
                listOf(
                    ReorderNode(id = "part-1", parentId = null, canHaveChildren = true),
                    ReorderNode(id = "ch-a", parentId = "part-1", canHaveChildren = false),
                )
            val chapters = listOf(FakeChapter(id = "ch-a", title = "Chapter A", partTitle = "Part One"))

            val move = ReorderNegotiator.resolveMove(nodes, draggedId = "ch-a", newParentId = null, targetIndex = 0)
            checkNotNull(move)

            val updated = applyReparentMove(chapters, move) { parentId -> if (parentId == null) null else "unused" }
            updated.first { it.id == "ch-a" }.partTitle shouldBe null
        }
    })
