package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class BookSyncPayloadContractTest :
    FunSpec({

        test("BookSyncPayload round-trips with all child lists populated") {
            val original = bookSyncPayloadFull()
            val json = contractJson.encodeToString(BookSyncPayload.serializer(), original)
            val decoded = contractJson.decodeFromString(BookSyncPayload.serializer(), json)
            decoded shouldBe original
        }

        test("BookSyncPayload round-trips with null cover") {
            val original = bookSyncPayloadMinimal().copy(cover = null)
            val json = contractJson.encodeToString(BookSyncPayload.serializer(), original)
            val decoded = contractJson.decodeFromString(BookSyncPayload.serializer(), json)
            decoded shouldBe original
        }

        // The polymorphic discriminator (`"type":"BookSyncPayload"`) is only injected when the
        // value is serialized via a sealed/polymorphic supertype — it does not appear when
        // encoding directly via `BookSyncPayload.serializer()`. Asserting on the wire field
        // names instead proves the contract shape didn't quietly drop fields, which is what
        // we actually care about for downstream consumers.
        test("BookSyncPayload wire shape includes every aggregate field name") {
            val json = contractJson.encodeToString(BookSyncPayload.serializer(), bookSyncPayloadFull())
            json shouldContain "\"contributors\""
            json shouldContain "\"chapters\""
            json shouldContain "\"audioFiles\""
            json shouldContain "\"series\""
            json shouldContain "\"cover\""
            json shouldContain "\"deletedAt\""
        }

        test("BookSyncPayload round-trips with hasScanWarning set") {
            val original = bookSyncPayloadMinimal().copy(hasScanWarning = true)
            val json = contractJson.encodeToString(BookSyncPayload.serializer(), original)
            val decoded = contractJson.decodeFromString(BookSyncPayload.serializer(), json)
            decoded shouldBe original
            decoded.hasScanWarning shouldBe true
        }

        test("BookSyncPayload routes through Tombstoned by deletedAt") {
            val tombstoned: Tombstoned = bookSyncPayloadMinimal().copy(deletedAt = 100L)
            tombstoned.deletedAt shouldBe 100L
        }

        test("BookSyncPayload round-trips with libraryId and folderId populated") {
            val original =
                bookSyncPayloadMinimal().copy(
                    libraryId = LibraryId("lib-001"),
                    folderId = FolderId("folder-001"),
                )
            val json = contractJson.encodeToString(BookSyncPayload.serializer(), original)
            val decoded = contractJson.decodeFromString(BookSyncPayload.serializer(), json)
            decoded shouldBe original
            decoded.libraryId shouldBe LibraryId("lib-001")
            decoded.folderId shouldBe FolderId("folder-001")
        }

        test("BookSyncPayload wire shape includes libraryId and folderId field names") {
            val json = contractJson.encodeToString(BookSyncPayload.serializer(), bookSyncPayloadFull())
            json shouldContain "\"libraryId\""
            json shouldContain "\"folderId\""
        }

        test("BookSyncPayload round-trips a populated fieldProvenance map across all three tiers") {
            val original =
                bookSyncPayloadMinimal().copy(
                    fieldProvenance =
                        mapOf(
                            BookField.TITLE to FieldProvenance(FieldSourceKind.USER, at = 111L),
                            BookField.DESCRIPTION to
                                FieldProvenance(FieldSourceKind.ENRICHMENT, provider = "audible", at = 222L),
                            BookField.PUBLISHER to FieldProvenance(FieldSourceKind.EMBEDDED, at = 333L),
                        ),
                )
            val json = contractJson.encodeToString(BookSyncPayload.serializer(), original)
            val decoded = contractJson.decodeFromString(BookSyncPayload.serializer(), json)
            decoded shouldBe original
            decoded.fieldProvenance[BookField.TITLE] shouldBe FieldProvenance(FieldSourceKind.USER, at = 111L)
            decoded.fieldProvenance[BookField.DESCRIPTION]?.provider shouldBe "audible"
            decoded.fieldProvenance[BookField.PUBLISHER]?.tier shouldBe 0
        }

        test("BookSyncPayload wire shape carries fieldProvenance") {
            val original =
                bookSyncPayloadMinimal().copy(
                    fieldProvenance = mapOf(BookField.TITLE to FieldProvenance(FieldSourceKind.USER, at = 1L)),
                )
            val json = contractJson.encodeToString(BookSyncPayload.serializer(), original)
            json shouldContain "\"fieldProvenance\""
            json shouldContain "\"TITLE\""
            json shouldContain "\"USER\""
        }
    })

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
        contributors = emptyList(),
        series = emptyList(),
        audioFiles = emptyList(),
        chapters = emptyList(),
        revision = 1L,
        updatedAt = 1730000000000L,
        createdAt = 1730000000000L,
        deletedAt = null,
    )

private fun bookSyncPayloadFull(): BookSyncPayload =
    BookSyncPayload(
        id = "book-2",
        libraryId = LibraryId("lib-001"),
        folderId = FolderId("folder-001"),
        title = "Words of Radiance",
        sortTitle = "Words of Radiance",
        subtitle = "Book Two of The Stormlight Archive",
        description = "Six years ago, the Assassin in White...",
        publishYear = 2014,
        publisher = "Tor Books",
        language = "en",
        isbn = "9780765326362",
        asin = "B00IWTSJES",
        abridged = false,
        explicit = false,
        totalDuration = 173_580_000L,
        cover = CoverPayload(source = CoverSource.EMBEDDED, hash = "deadbeef"),
        rootRelPath = "stormlight/words-of-radiance",
        inode = 314159L,
        scannedAt = 1730000000000L,
        contributors =
            listOf(
                BookContributorPayload(
                    id = "contrib-1",
                    name = "Brandon Sanderson",
                    sortName = "Sanderson, Brandon",
                    role = "author",
                    creditedAs = null,
                ),
                BookContributorPayload(
                    id = "contrib-2",
                    name = "Michael Kramer",
                    sortName = "Kramer, Michael",
                    role = "narrator",
                    creditedAs = null,
                ),
            ),
        series =
            listOf(
                BookSeriesPayload(
                    id = "series-1",
                    name = "The Stormlight Archive",
                    sequence = "2",
                ),
            ),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "file-1",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 86_400_000L,
                    size = 1_073_741_824L,
                ),
                BookAudioFilePayload(
                    id = "file-2",
                    index = 1,
                    filename = "02.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 87_180_000L,
                    size = 1_080_000_000L,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(
                    id = "chap-1",
                    title = "Prologue",
                    duration = 1_200_000L,
                    startTime = 0L,
                ),
                BookChapterPayload(
                    id = "chap-2",
                    title = "Chapter 1",
                    duration = 2_400_000L,
                    startTime = 1_200_000L,
                ),
            ),
        revision = 42L,
        updatedAt = 1730000005000L,
        createdAt = 1729000000000L,
        deletedAt = null,
    )
