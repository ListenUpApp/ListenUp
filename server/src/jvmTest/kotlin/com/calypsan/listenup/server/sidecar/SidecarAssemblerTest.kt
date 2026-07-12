package com.calypsan.listenup.server.sidecar

import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.api.sync.UserEditedField
import com.calypsan.listenup.server.testing.bookPayloadFixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for [SidecarAssembler] — the pure DB-aggregate → [ListenUpSidecar] projection.
 * No filesystem, no database: every test builds a [com.calypsan.listenup.api.sync.BookSyncPayload]
 * fixture directly and asserts on the assembled sidecar.
 */
class SidecarAssemblerTest :
    FunSpec({

        val assembler = SidecarAssembler()

        test("a protected title and contributors round-trip into identity + metadata") {
            val book =
                bookPayloadFixture(
                    id = "book1",
                    title = "The Way of Kings",
                    contributors =
                        listOf(
                            BookContributorPayload(
                                id = "c1",
                                name = "Brandon Sanderson",
                                sortName = null,
                                role = "author",
                                creditedAs = null,
                            ),
                        ),
                ).copy(
                    asin = "B0ABCDEFG",
                    userEditedFields = setOf(UserEditedField.TITLE),
                )

            val sidecar = assembler.assemble(book)

            sidecar.schemaVersion shouldBe 1
            sidecar.identity.asin shouldBe "B0ABCDEFG"
            sidecar.identity.titleAuthor shouldBe "The Way of Kings / Brandon Sanderson"
            sidecar.metadata.title shouldBe "The Way of Kings"
            sidecar.metadata.contributors shouldBe listOf(SidecarContributor(name = "Brandon Sanderson", role = "author"))
            sidecar.userEditedFields shouldBe listOf("TITLE")
        }

        test("USER chapters are included with their source and entries") {
            val book =
                bookPayloadFixture(id = "book1", title = "Book")
                    .copy(
                        chapterSource = ChapterSource.USER,
                        chapters =
                            listOf(
                                BookChapterPayload(id = "ch1", title = "Prelude", duration = 60_000L, startTime = 0L),
                                BookChapterPayload(id = "ch2", title = "Prologue", duration = 120_000L, startTime = 60_000L),
                            ),
                    )

            val sidecar = assembler.assemble(book)

            val chapters = sidecar.chapters
            chapters.shouldNotBeNull()
            chapters.source shouldBe "USER"
            chapters.entries shouldBe
                listOf(
                    SidecarChapter(title = "Prelude", startMs = 0L),
                    SidecarChapter(title = "Prologue", startMs = 60_000L),
                )
        }

        test("scanner-sourced (EMBEDDED) chapters are excluded — chapters is null") {
            val book =
                bookPayloadFixture(id = "book1", title = "Book")
                    .copy(
                        chapterSource = ChapterSource.EMBEDDED,
                        chapters =
                            listOf(
                                BookChapterPayload(id = "ch1", title = "Ch 1", duration = 60_000L, startTime = 0L),
                            ),
                    )

            val sidecar = assembler.assemble(book)

            sidecar.chapters.shouldBeNull()
        }

        test("the chapter fingerprint is derived from the current chapter set regardless of source") {
            val book =
                bookPayloadFixture(id = "book1", title = "Book")
                    .copy(
                        chapterSource = ChapterSource.EMBEDDED,
                        chapters =
                            listOf(
                                BookChapterPayload(id = "ch1", title = "Ch 1", duration = 60_000L, startTime = 0L),
                            ),
                    )

            val sidecar = assembler.assemble(book)

            sidecar.identity.chapterFingerprint.shouldNotBeNull()
        }

        test("no chapters at all — fingerprint is null") {
            val book = bookPayloadFixture(id = "book1", title = "Book")

            val sidecar = assembler.assemble(book)

            sidecar.identity.chapterFingerprint.shouldBeNull()
        }

        test("userEditedFields mirrors the DB set in declaration order") {
            val book =
                bookPayloadFixture(id = "book1", title = "Book")
                    .copy(userEditedFields = setOf(UserEditedField.SERIES, UserEditedField.TITLE))

            val sidecar = assembler.assemble(book)

            sidecar.userEditedFields shouldBe listOf("TITLE", "SERIES")
        }

        test("series and genres carry through to metadata") {
            val book =
                bookPayloadFixture(
                    id = "book1",
                    title = "Book",
                    series = listOf(BookSeriesPayload(id = "s1", name = "The Stormlight Archive", sequence = "1")),
                )

            val sidecar = assembler.assemble(book)

            sidecar.metadata.series shouldBe listOf(SidecarSeriesEntry(name = "The Stormlight Archive", sequence = "1"))
        }

        test("tag names are emitted in stable sorted order") {
            val book = bookPayloadFixture(id = "book1", title = "Book")

            val sidecar = assembler.assemble(book, tagNames = listOf("zeta", "alpha", "Favorites"))

            sidecar.metadata.tags shouldBe listOf("Favorites", "alpha", "zeta")
        }

        test("no tags — metadata.tags is empty") {
            val book = bookPayloadFixture(id = "book1", title = "Book")

            val sidecar = assembler.assemble(book)

            sidecar.metadata.tags.shouldBeEmpty()
        }

        test("no authors — titleAuthor falls back to the title alone") {
            val book = bookPayloadFixture(id = "book1", title = "Book With No Author")

            val sidecar = assembler.assemble(book)

            sidecar.identity.titleAuthor shouldBe "Book With No Author"
        }

        test("readingOrders is always empty — the opaque stub is reserved for a later phase") {
            val book = bookPayloadFixture(id = "book1", title = "Book")

            val sidecar = assembler.assemble(book)

            sidecar.readingOrders.shouldBeEmpty()
        }
    })
