package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The two-tier chapter-grouping fields on the wire.
 *
 * A book names its own structure — "Part"/"Book" for a novel, "Sequence"/"Era" for something that
 * does not fit that vocabulary — and individual chapters carry the headers that open each group.
 * Both halves ride the existing book payload rather than a domain of their own, so the test that
 * matters is the **default**: every one of these fields must decode from a payload written by a
 * server that has never heard of them. Get that wrong and a client upgrade bricks against an older
 * server, which is precisely the failure the wire-compat defaults exist to prevent.
 */
private fun payload(
    bookTierLabel: String? = null,
    partTierLabel: String? = null,
    chapters: List<BookChapterPayload> = emptyList(),
) = BookSyncPayload(
    id = "book-1",
    libraryId = LibraryId("lib-1"),
    folderId = FolderId("folder-1"),
    title = "The Way of Kings",
    sortTitle = null,
    subtitle = null,
    description = null,
    publishYear = null,
    publisher = null,
    language = null,
    isbn = null,
    asin = null,
    abridged = false,
    explicit = false,
    totalDuration = 1_000L,
    cover = null,
    rootRelPath = "Sanderson/The Way of Kings",
    inode = null,
    scannedAt = 0L,
    contributors = emptyList(),
    series = emptyList(),
    audioFiles = emptyList(),
    chapters = chapters,
    bookTierLabel = bookTierLabel,
    partTierLabel = partTierLabel,
    revision = 1L,
    updatedAt = 0L,
    createdAt = 0L,
    deletedAt = null,
)

class BookTierLabelsContractTest :
    FunSpec({

        test("a book's tier vocabulary round-trips") {
            val v = payload(bookTierLabel = "Volume", partTierLabel = "Sequence")
            contractJson.decodeFromString<BookSyncPayload>(contractJson.encodeToString(v)) shouldBe v
        }

        test("per-chapter section headers round-trip") {
            val v =
                payload(
                    chapters =
                        listOf(
                            BookChapterPayload(
                                id = "ch-1",
                                title = "Prologue",
                                duration = 500L,
                                startTime = 0L,
                                partTitle = "The Way of Kings",
                                bookTitle = "Book One",
                            ),
                        ),
                )
            contractJson.decodeFromString<BookSyncPayload>(contractJson.encodeToString(v)) shouldBe v
        }

        test("an unnamed tier is null, and a named one survives beside it") {
            val v = payload(bookTierLabel = null, partTierLabel = "Part")
            val round = contractJson.decodeFromString<BookSyncPayload>(contractJson.encodeToString(v))
            round.bookTierLabel shouldBe null
            round.partTierLabel shouldBe "Part"
        }

        test("a payload from a server that predates tiers still decodes") {
            val v =
                contractJson.decodeFromString<BookSyncPayload>(
                    contractJson
                        .encodeToString(payload(bookTierLabel = "Volume", partTierLabel = "Part"))
                        .withoutKeys("bookTierLabel", "partTierLabel"),
                )
            v.bookTierLabel shouldBe null
            v.partTierLabel shouldBe null
        }

        test("a chapter from a server that predates section headers still decodes") {
            val v =
                contractJson.decodeFromString<BookChapterPayload>(
                    """{"id":"c","title":"Ch 1","duration":10,"startTime":0}""",
                )
            v.partTitle shouldBe null
            v.bookTitle shouldBe null
        }
    })

/**
 * Strips top-level `"key":<value>,` entries from an encoded object — the cheapest honest way to
 * build "what an older server would have sent" without hand-maintaining a full literal that drifts
 * every time the payload gains an unrelated field.
 */
private fun String.withoutKeys(vararg keys: String): String =
    keys.fold(this) { json, key ->
        json.replace(Regex(""","$key":(null|"[^"]*")"""), "")
    }
