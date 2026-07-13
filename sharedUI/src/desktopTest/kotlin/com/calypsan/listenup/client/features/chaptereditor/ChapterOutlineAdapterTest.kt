package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.design.reorderable.ReorderMove
import com.calypsan.listenup.client.design.reorderable.ReorderNode
import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun chapter(
    id: String,
    startTime: Long,
    partTitle: String? = null,
    bookTitle: String? = null,
) = Chapter(
    id = id,
    title = id,
    duration = 60_000L,
    startTime = startTime,
    partTitle = partTitle,
    bookTitle = bookTitle,
)

class ChapterOutlineAdapterTest :
    FunSpec({
        // Book One
        //   ├── Prologue: ch1, ch2
        //   └── Part One: ch3, ch4
        // Book Two
        //   └── Part One: ch5, ch6
        val chapters =
            listOf(
                chapter("ch1", startTime = 0L, partTitle = "Prologue", bookTitle = "Book One"),
                chapter("ch2", startTime = 60_000L),
                chapter("ch3", startTime = 120_000L, partTitle = "Part One"),
                chapter("ch4", startTime = 180_000L),
                chapter("ch5", startTime = 240_000L, partTitle = "Part One", bookTitle = "Book Two"),
                chapter("ch6", startTime = 300_000L),
            )

        val flatChapters =
            listOf(
                chapter("f1", startTime = 0L),
                chapter("f2", startTime = 60_000L),
                chapter("f3", startTime = 120_000L),
            )

        test(
            "a flat (header-free) chapter list produces root-level leaf nodes only, " +
                "with no header node emitted for the implicit null-titled groups",
        ) {
            val nodes = flatChapters.toReorderNodes()

            nodes shouldBe
                listOf(
                    ReorderNode("f1", parentId = null, canHaveChildren = false),
                    ReorderNode("f2", parentId = null, canHaveChildren = false),
                    ReorderNode("f3", parentId = null, canHaveChildren = false),
                )
        }

        test("book and part headers become parent nodes; chapters become leaves under the innermost open header") {
            val nodes = chapters.toReorderNodes()
            val byId = nodes.associateBy { it.id }

            byId.getValue("book-header-0").let {
                it.parentId shouldBe null
                it.canHaveChildren shouldBe true
            }
            byId.getValue("part-header-0-0").let {
                it.parentId shouldBe "book-header-0"
                it.canHaveChildren shouldBe true
            }
            byId.getValue("part-header-0-1").parentId shouldBe "book-header-0"
            byId.getValue("book-header-1").parentId shouldBe null

            // Chapters nest under the innermost open header (the part, not the book).
            byId.getValue("ch1").let {
                it.parentId shouldBe "part-header-0-0"
                it.canHaveChildren shouldBe false
            }
            byId.getValue("ch2").parentId shouldBe "part-header-0-0"
            byId.getValue("ch3").parentId shouldBe "part-header-0-1"
            byId.getValue("ch4").parentId shouldBe "part-header-0-1"
            byId.getValue("ch5").parentId shouldBe "part-header-1-0"
            byId.getValue("ch6").parentId shouldBe "part-header-1-0"
        }

        test(
            "moving a chapter leaf under a different part header relabels only that chapter, " +
                "not the target header or siblings",
        ) {
            val nodes = chapters.toReorderNodes()

            val move = ReorderMove(movedId = "ch1", newParentId = "part-header-0-1", newIndex = 0)
            val edit = interpretMove(nodes, chapters, move)

            edit shouldBe OutlineEdit.RelabelChapter(chapterId = "ch1", partTitle = "Part One", bookTitle = "Book One")
        }

        test("moving a chapter leaf across books relabels it with the target book's own title, not its origin book's") {
            val nodes = chapters.toReorderNodes()

            val move = ReorderMove(movedId = "ch1", newParentId = "part-header-1-0", newIndex = 0)
            val edit = interpretMove(nodes, chapters, move)

            edit shouldBe OutlineEdit.RelabelChapter(chapterId = "ch1", partTitle = "Part One", bookTitle = "Book Two")
        }

        test("moving a header node reorders its whole group's position, not any chapter's labels") {
            val nodes = chapters.toReorderNodes()

            // Move "Part One" (part-header-0-1, holding ch3/ch4) ahead of "Prologue" within Book One.
            val move = ReorderMove(movedId = "part-header-0-1", newParentId = "book-header-0", newIndex = 0)
            val edit = interpretMove(nodes, chapters, move)

            edit shouldBe OutlineEdit.Reorder(orderedChapterIds = listOf("ch3", "ch4", "ch1", "ch2", "ch5", "ch6"))
        }

        test(
            "no code path in this file ever synthesizes a label string — " +
                "every RelabelChapter carries only caller-supplied text",
        ) {
            val nodes = chapters.toReorderNodes()

            // Dropped into an existing titled group: the labels are exactly that group's existing
            // titles (copied from ch3/ch4, the chapters already in part-header-0-1) — never a
            // prefixed, numbered, or otherwise fabricated string.
            val existingPartTitle = chapters.first { it.id == "ch3" }.partTitle
            val existingBookTitle = chapters.first { it.id == "ch1" }.bookTitle
            val moveIntoGroup = ReorderMove(movedId = "ch1", newParentId = "part-header-0-1", newIndex = 0)
            val intoGroup = interpretMove(nodes, chapters, moveIntoGroup)
            intoGroup.shouldBeInstanceOf<OutlineEdit.RelabelChapter>()
            intoGroup.partTitle shouldBe existingPartTitle
            intoGroup.bookTitle shouldBe existingBookTitle

            // Dropped to root: both labels clear to null — never defaulted to a placeholder string.
            val toRoot = interpretMove(nodes, chapters, ReorderMove(movedId = "ch1", newParentId = null, newIndex = 0))
            toRoot shouldBe OutlineEdit.RelabelChapter(chapterId = "ch1", partTitle = null, bookTitle = null)
        }
    })
