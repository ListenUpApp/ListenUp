package com.calypsan.listenup.api

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookGenrePayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Round-trips [GenreSyncPayload] through [contractJson] and pins the backwards-compatible default
 * on [BookSyncPayload.genres]. Catches field-name drift, default-value regression, and the
 * forward-compat decode case where a pre-Genres `BookSyncPayload` omits the `genres` key entirely.
 */
class GenreSyncPayloadContractTest :
    FunSpec({

        test("should round-trip a full GenreSyncPayload") {
            val original =
                GenreSyncPayload(
                    id = "g-fantasy",
                    name = "Fantasy",
                    slug = "fantasy",
                    path = "/fiction/fantasy",
                    parentId = "g-fiction",
                    depth = 1,
                    sortOrder = 10,
                    color = "#abcdef",
                    description = "Magic, dragons, and the stuff dreams are made of.",
                    revision = 7L,
                    updatedAt = 1_700_000_000L,
                    createdAt = 1_700_000_000L,
                    deletedAt = null,
                )
            roundTrip<GenreSyncPayload>(original) shouldBe original
        }

        test("should round-trip a tombstoned GenreSyncPayload (deletedAt non-null)") {
            val original =
                GenreSyncPayload(
                    id = "g-erotica",
                    name = "Erotica",
                    slug = "erotica",
                    path = "/erotica",
                    parentId = null,
                    depth = 0,
                    sortOrder = 0,
                    color = null,
                    description = null,
                    revision = 11L,
                    updatedAt = 1_700_001_000L,
                    createdAt = 1_700_000_000L,
                    deletedAt = 1_700_001_000L,
                )
            roundTrip<GenreSyncPayload>(original) shouldBe original
            roundTrip<GenreSyncPayload>(original).deletedAt shouldBe 1_700_001_000L
        }

        test("should round-trip a root GenreSyncPayload with null parentId and depth 0") {
            val original =
                GenreSyncPayload(
                    id = "g-fiction",
                    name = "Fiction",
                    slug = "fiction",
                    path = "/fiction",
                )
            val decoded = roundTrip<GenreSyncPayload>(original)
            decoded shouldBe original
            decoded.parentId shouldBe null
            decoded.depth shouldBe 0
        }

        test("should round-trip a BookGenrePayload denormalized reference") {
            val original =
                BookGenrePayload(
                    id = "g-fantasy",
                    name = "Fantasy",
                    slug = "fantasy",
                    path = "/fiction/fantasy",
                )
            roundTrip<BookGenrePayload>(original) shouldBe original
        }

        test("should round-trip BookSyncPayload with genres populated") {
            val original =
                bookSyncPayloadMinimal().copy(
                    genres =
                        listOf(
                            BookGenrePayload(
                                id = "g-fantasy",
                                name = "Fantasy",
                                slug = "fantasy",
                                path = "/fiction/fantasy",
                            ),
                            BookGenrePayload(
                                id = "g-epic",
                                name = "Epic Fantasy",
                                slug = "epic-fantasy",
                                path = "/fiction/fantasy/epic-fantasy",
                            ),
                        ),
                )
            val decoded = roundTrip<BookSyncPayload>(original)
            decoded shouldBe original
            decoded.genres shouldContainExactly original.genres
        }

        test("should default BookSyncPayload.genres to emptyList when JSON omits the field") {
            // Simulates a payload serialized before Genres landed, which lacks the "genres" key.
            // The default `genres = emptyList()` keeps pre-Genres SSE replay and persisted-state
            // decode forward-compatible.
            val jsonWithoutGenres =
                """
                {
                    "id": "book-x",
                    "libraryId": "lib-001",
                    "folderId": "folder-001",
                    "title": "Pre-Genres Book",
                    "sortTitle": null,
                    "subtitle": null,
                    "description": null,
                    "publishYear": null,
                    "publisher": null,
                    "language": null,
                    "isbn": null,
                    "asin": null,
                    "abridged": false,
                    "explicit": false,
                    "totalDuration": 0,
                    "cover": null,
                    "rootRelPath": "x",
                    "inode": null,
                    "scannedAt": 1730000000000,
                    "contributors": [],
                    "series": [],
                    "audioFiles": [],
                    "chapters": [],
                    "revision": 1,
                    "updatedAt": 1730000000000,
                    "createdAt": 1730000000000,
                    "deletedAt": null
                }
                """.trimIndent()
            val decoded = contractJson.decodeFromString<BookSyncPayload>(jsonWithoutGenres)
            decoded.genres.shouldBeEmpty()
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = contractJson.decodeFromString<T>(contractJson.encodeToString(value))

private fun bookSyncPayloadMinimal(): BookSyncPayload =
    BookSyncPayload(
        id = "book-1",
        libraryId = LibraryId("lib-001"),
        folderId = FolderId("folder-001"),
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
        totalDuration = 0L,
        cover = CoverPayload(source = CoverSource.FILESYSTEM, hash = "abc"),
        rootRelPath = "stormlight/way-of-kings",
        inode = null,
        scannedAt = 1730000000000L,
        contributors = emptyList<BookContributorPayload>(),
        series = emptyList<BookSeriesPayload>(),
        audioFiles = emptyList<BookAudioFilePayload>(),
        chapters = emptyList<BookChapterPayload>(),
        revision = 1L,
        updatedAt = 1730000000000L,
        createdAt = 1730000000000L,
        deletedAt = null,
    )
