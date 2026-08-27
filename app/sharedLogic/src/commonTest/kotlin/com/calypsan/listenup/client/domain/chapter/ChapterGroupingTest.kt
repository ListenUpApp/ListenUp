package com.calypsan.listenup.client.domain.chapter

import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [groupChapters] — turning a flat, ordered chapter list into its Book → Part → Chapter shape.
 *
 * Headers live on the chapter that opens a group, so grouping is a fold over the list rather than
 * a join across stored parent ids. That choice is what these tests pin: nothing is renumbered when
 * a chapter moves, a chapter can open both tiers at once, and the flat case — which is nearly
 * every book — comes back as one anonymous group so the UI needs no branch for it.
 */
private fun chapter(
    id: String,
    partTitle: String? = null,
    bookTitle: String? = null,
) = Chapter(id = id, title = id, duration = 100L, startTime = 0L, partTitle = partTitle, bookTitle = bookTitle)

class ChapterGroupingTest :
    FunSpec({

        test("a header-free list is one anonymous book holding one anonymous part") {
            val groups = listOf(chapter("c1"), chapter("c2")).groupChapters()

            withClue("the flat case is most books; it must not need a special path in the UI") {
                groups.size shouldBe 1
                groups[0].title shouldBe null
                groups[0].parts.size shouldBe 1
                groups[0].parts[0].title shouldBe null
                groups[0].parts[0].chapters.map { it.id } shouldBe listOf("c1", "c2")
            }
        }

        test("an empty list groups to nothing") {
            emptyList<Chapter>().groupChapters() shouldBe emptyList()
        }

        test("a part header opens a group that runs until the next one") {
            val groups =
                listOf(
                    chapter("c1", partTitle = "Part One"),
                    chapter("c2"),
                    chapter("c3", partTitle = "Part Two"),
                ).groupChapters()

            val parts = groups.single().parts
            parts.map { it.title } shouldBe listOf("Part One", "Part Two")
            parts[0].chapters.map { it.id } shouldBe listOf("c1", "c2")
            parts[1].chapters.map { it.id } shouldBe listOf("c3")
        }

        test("chapters before the first header land in an anonymous leading group") {
            val groups =
                listOf(
                    chapter("intro"),
                    chapter("c1", partTitle = "Part One"),
                ).groupChapters()

            val parts = groups.single().parts
            withClue("a preface belongs to the book, not to the first Part that happens to follow it") {
                parts.map { it.title } shouldBe listOf(null, "Part One")
                parts[0].chapters.map { it.id } shouldBe listOf("intro")
            }
        }

        test("a book header opens a book, and closes the part that was open inside the previous one") {
            val groups =
                listOf(
                    chapter("c1", bookTitle = "Book One", partTitle = "Part One"),
                    chapter("c2"),
                    chapter("c3", bookTitle = "Book Two"),
                    chapter("c4", partTitle = "Part One"),
                ).groupChapters()

            groups.map { it.title } shouldBe listOf("Book One", "Book Two")
            groups[0].parts.map { it.title } shouldBe listOf("Part One")
            withClue("Book Two's own leading chapter is not inside Book One's Part One") {
                groups[1].parts.map { it.title } shouldBe listOf(null, "Part One")
                groups[1].parts[0].chapters.map { it.id } shouldBe listOf("c3")
                groups[1].parts[1].chapters.map { it.id } shouldBe listOf("c4")
            }
        }

        test("the same part name may recur under different books without merging") {
            // "Part One" of Book Two is not "Part One" of Book One. Grouping by name rather than by
            // position would silently fuse them.
            val groups =
                listOf(
                    chapter("a1", bookTitle = "Book One", partTitle = "Part One"),
                    chapter("b1", bookTitle = "Book Two", partTitle = "Part One"),
                ).groupChapters()

            groups.size shouldBe 2
            groups[0]
                .parts
                .single()
                .chapters
                .map { it.id } shouldBe listOf("a1")
            groups[1]
                .parts
                .single()
                .chapters
                .map { it.id } shouldBe listOf("b1")
        }

        test("every chapter appears exactly once, whatever the header arrangement") {
            val all =
                listOf(
                    chapter("c0"),
                    chapter("c1", bookTitle = "Book One"),
                    chapter("c2", partTitle = "Part One"),
                    chapter("c3"),
                    chapter("c4", bookTitle = "Book Two", partTitle = "Part One"),
                    chapter("c5", partTitle = "Part Two"),
                )

            val flattened = all.groupChapters().flatMap { book -> book.parts.flatMap { it.chapters } }

            withClue("grouping is a view of the list; losing or duplicating a chapter is data loss") {
                flattened.map { it.id } shouldBe all.map { it.id }
            }
        }
    })
