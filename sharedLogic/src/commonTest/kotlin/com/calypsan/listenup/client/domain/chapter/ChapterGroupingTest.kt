package com.calypsan.listenup.client.domain.chapter

import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun ch(
    id: String,
    start: Long,
    part: String? = null,
    book: String? = null,
) = Chapter(id = id, title = id, duration = 1000, startTime = start, partTitle = part, bookTitle = book)

class ChapterGroupingTest :
    FunSpec({

        test("no headers yields a single null-titled book with a single null-titled part holding every chapter") {
            val chapters = listOf(ch("a", 0), ch("b", 1000), ch("c", 2000))
            val groups = chapters.groupChapters()
            groups.size shouldBe 1
            groups[0].title shouldBe null
            groups[0].parts.size shouldBe 1
            groups[0].parts[0].title shouldBe null
            groups[0].parts[0].chapters.map { it.id } shouldBe listOf("a", "b", "c")
        }

        test("part headers only produce one tier under a null-titled book") {
            val chapters =
                listOf(
                    ch("a", 0, part = "Part One"),
                    ch("b", 1000),
                    ch("c", 2000, part = "Part Two"),
                    ch("d", 3000),
                )
            val groups = chapters.groupChapters()
            groups.size shouldBe 1
            groups[0].title shouldBe null
            groups[0].parts.map { it.title } shouldBe listOf("Part One", "Part Two")
            groups[0].parts[0].chapters.map { it.id } shouldBe listOf("a", "b")
            groups[0].parts[1].chapters.map { it.id } shouldBe listOf("c", "d")
        }

        test("book and part headers produce two tiers") {
            val chapters =
                listOf(
                    ch("a", 0, part = "Part One", book = "Book I"),
                    ch("b", 1000),
                    ch("c", 2000, part = "Part Two"),
                    ch("d", 3000, part = "Part One", book = "Book II"),
                    ch("e", 4000),
                )
            val groups = chapters.groupChapters()
            groups.map { it.title } shouldBe listOf("Book I", "Book II")
            groups[0].parts.map { it.title } shouldBe listOf("Part One", "Part Two")
            groups[0].parts[0].chapters.map { it.id } shouldBe listOf("a", "b")
            groups[0].parts[1].chapters.map { it.id } shouldBe listOf("c")
            groups[1].parts.map { it.title } shouldBe listOf("Part One")
            groups[1].parts[0].chapters.map { it.id } shouldBe listOf("d", "e")
        }

        test("book header without part header opens a null-titled part") {
            val chapters =
                listOf(
                    ch("a", 0, book = "Book I"),
                    ch("b", 1000),
                )
            val groups = chapters.groupChapters()
            groups.single().title shouldBe "Book I"
            groups
                .single()
                .parts
                .single()
                .title shouldBe null
            groups
                .single()
                .parts
                .single()
                .chapters
                .map { it.id } shouldBe listOf("a", "b")
        }

        test("chapters before the first header fall into a null-titled leading group") {
            val chapters =
                listOf(
                    ch("intro", 0),
                    ch("a", 1000, part = "Part One"),
                    ch("b", 2000),
                )
            val groups = chapters.groupChapters()
            groups.size shouldBe 1
            groups[0].parts.map { it.title } shouldBe listOf(null, "Part One")
            groups[0].parts[0].chapters.map { it.id } shouldBe listOf("intro")
            groups[0].parts[1].chapters.map { it.id } shouldBe listOf("a", "b")
        }

        test("empty list yields no groups") {
            emptyList<Chapter>().groupChapters() shouldBe emptyList()
        }

        test("300 chapters across 10 books group without loss") {
            val chapters =
                (0 until 300).map { i ->
                    ch(
                        id = "c$i",
                        start = i * 1000L,
                        part = if (i % 30 == 0) "Part ${i / 30}" else null,
                        book = if (i % 30 == 0) "Book ${i / 30}" else null,
                    )
                }
            val groups = chapters.groupChapters()
            groups.size shouldBe 10
            groups.sumOf { b -> b.parts.sumOf { it.chapters.size } } shouldBe 300
            groups.forEach { book ->
                book.parts.size shouldBe 1
                book.parts
                    .single()
                    .chapters.size shouldBe 30
            }
        }
    })
