package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

// Timestamp epoch-ms constants used by both the payload fixtures and assertions.
private const val ENTITY_CREATED_AT_MS = 1_600_000_000_000L
private const val ENTITY_UPDATED_AT_MS = 1_600_000_001_000L
private const val PAYLOAD_CREATED_AT_MS = 1_700_000_000_000L
private const val PAYLOAD_UPDATED_AT_MS = 1_700_000_001_000L
private const val PAYLOAD_DELETED_AT_MS = 1_700_000_099_000L

// Default numeric values for bookPayload() helper.
private const val BOOK_TOTAL_DURATION_MS = 72_000_000L
private const val BOOK_PUBLISH_YEAR = 2010
private const val NON_DEFAULT_REVISION = 42L

/**
 * Tests for [BookEntityMapper.toBookEntity].
 *
 * Verifies that:
 * - Wire fields from [BookSyncPayload] are carried through correctly.
 * - The client-computed blur hash is preserved from an existing [BookEntity] row and never
 *   overwritten with null on sync.
 * - When [existing] is null (first-seen book), the blur hash defaults to null.
 */
class BookEntityMapperTest :
    FunSpec({
        val mapper = BookEntityMapper()

        // --- Test helpers ---

        fun bookPayload(
            id: String = "book-1",
            title: String = "The Way of Kings",
            sortTitle: String? = "Way of Kings, The",
            subtitle: String? = "The Stormlight Archive",
            description: String? = "A fantasy epic.",
            publishYear: Int? = BOOK_PUBLISH_YEAR,
            publisher: String? = "Tor Books",
            language: String? = "en",
            isbn: String? = "978-0-7653-2637-9",
            asin: String? = "B003P2WO5E",
            abridged: Boolean = false,
            totalDuration: Long = BOOK_TOTAL_DURATION_MS,
            cover: CoverPayload? = CoverPayload(source = CoverSource.FILESYSTEM, hash = "abc123"),
            revision: Long = 1L,
            deletedAt: Long? = null,
            createdAt: Long = PAYLOAD_CREATED_AT_MS,
            updatedAt: Long = PAYLOAD_UPDATED_AT_MS,
        ): BookSyncPayload =
            BookSyncPayload(
                id = id,
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = title,
                sortTitle = sortTitle,
                subtitle = subtitle,
                description = description,
                publishYear = publishYear,
                publisher = publisher,
                language = language,
                isbn = isbn,
                asin = asin,
                abridged = abridged,
                explicit = false,
                totalDuration = totalDuration,
                cover = cover,
                rootRelPath = "books/way-of-kings",
                inode = null,
                scannedAt = PAYLOAD_CREATED_AT_MS,
                contributors = emptyList(),
                series = emptyList(),
                audioFiles = emptyList(),
                chapters = emptyList(),
                revision = revision,
                updatedAt = updatedAt,
                createdAt = createdAt,
                deletedAt = deletedAt,
            )

        fun bookEntity(
            id: BookId = BookId("book-1"),
            coverBlurHash: String? = "L5H2EC=PM+yV",
            coverHash: String? = null,
            coverDownloadedAt: Timestamp? = null,
        ): BookEntity =
            BookEntity(
                id = id,
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = "Old Title",
                totalDuration = 1_000L,
                coverBlurHash = coverBlurHash,
                coverHash = coverHash,
                coverDownloadedAt = coverDownloadedAt,
                createdAt = Timestamp(ENTITY_CREATED_AT_MS),
                updatedAt = Timestamp(ENTITY_UPDATED_AT_MS),
            )

        // --- Tests ---

        test("toBookEntity carries wire fields through from payload") {
            val payload = bookPayload()
            val result = mapper.toBookEntity(payload, existing = null)

            result.id shouldBe BookId("book-1")
            result.title shouldBe "The Way of Kings"
            result.sortTitle shouldBe "Way of Kings, The"
            result.subtitle shouldBe "The Stormlight Archive"
            result.description shouldBe "A fantasy epic."
            result.publishYear shouldBe BOOK_PUBLISH_YEAR
            result.publisher shouldBe "Tor Books"
            result.language shouldBe "en"
            result.isbn shouldBe "978-0-7653-2637-9"
            result.asin shouldBe "B003P2WO5E"
            result.abridged shouldBe false
            result.totalDuration shouldBe BOOK_TOTAL_DURATION_MS
        }

        test("toBookEntity maps revision and deletedAt from payload") {
            val payload = bookPayload(revision = NON_DEFAULT_REVISION, deletedAt = PAYLOAD_DELETED_AT_MS)
            val result = mapper.toBookEntity(payload, existing = null)

            result.revision shouldBe NON_DEFAULT_REVISION
            result.deletedAt shouldBe PAYLOAD_DELETED_AT_MS
        }

        test("toBookEntity maps createdAt and updatedAt via Long to Timestamp conversion") {
            val payload = bookPayload(createdAt = PAYLOAD_CREATED_AT_MS, updatedAt = PAYLOAD_UPDATED_AT_MS)
            val result = mapper.toBookEntity(payload, existing = null)

            result.createdAt shouldBe Timestamp(PAYLOAD_CREATED_AT_MS)
            result.updatedAt shouldBe Timestamp(PAYLOAD_UPDATED_AT_MS)
        }

        test("toBookEntity extracts coverHash from payload cover hash") {
            val payload = bookPayload(cover = CoverPayload(source = CoverSource.EMBEDDED, hash = "deadbeef"))
            val result = mapper.toBookEntity(payload, existing = null)

            result.coverHash shouldBe "deadbeef"
        }

        test("toBookEntity sets coverHash to null when payload cover is null") {
            val payload = bookPayload(cover = null)
            val result = mapper.toBookEntity(payload, existing = null)

            result.coverHash.shouldBeNull()
        }

        test("toBookEntity with existing null sets coverBlurHash to null") {
            val payload = bookPayload()
            val result = mapper.toBookEntity(payload, existing = null)

            result.coverBlurHash.shouldBeNull()
        }

        test("toBookEntity preserves coverBlurHash from existing row") {
            val existing = bookEntity(coverBlurHash = "L5H2EC=PM+yV")
            val payload = bookPayload()
            val result = mapper.toBookEntity(payload, existing = existing)

            result.coverBlurHash shouldBe "L5H2EC=PM+yV"
        }

        test("toBookEntity does not carry over other fields from existing row — wire payload wins") {
            // The existing row has a stale title; the wire payload carries the new one.
            val existing = bookEntity() // title = "Old Title"
            val payload = bookPayload(title = "New Title from Server")
            val result = mapper.toBookEntity(payload, existing = existing)

            result.title shouldBe "New Title from Server"
        }

        test("toBookEntity preserves coverDownloadedAt from existing row when coverHash is unchanged") {
            val downloadedAt = Timestamp(ENTITY_UPDATED_AT_MS)
            val existing = bookEntity(coverHash = "abc123", coverDownloadedAt = downloadedAt)
            val payload = bookPayload(cover = CoverPayload(source = CoverSource.FILESYSTEM, hash = "abc123"))
            val result = mapper.toBookEntity(payload, existing = existing)

            result.coverDownloadedAt shouldBe downloadedAt
        }

        test("toBookEntity clears coverDownloadedAt when the server cover hash changed") {
            val downloadedAt = Timestamp(ENTITY_UPDATED_AT_MS)
            val existing = bookEntity(coverHash = "old-hash", coverDownloadedAt = downloadedAt)
            val payload = bookPayload(cover = CoverPayload(source = CoverSource.FILESYSTEM, hash = "new-hash"))
            val result = mapper.toBookEntity(payload, existing = existing)

            result.coverDownloadedAt.shouldBeNull()
        }

        test("toBookEntity with existing null sets coverDownloadedAt to null") {
            val payload = bookPayload()
            val result = mapper.toBookEntity(payload, existing = null)

            result.coverDownloadedAt.shouldBeNull()
        }

        test("toBookEntity carries hasScanWarning from payload — true and false") {
            mapper
                .toBookEntity(bookPayload().copy(hasScanWarning = true), existing = null)
                .hasScanWarning shouldBe true
            mapper
                .toBookEntity(bookPayload().copy(hasScanWarning = false), existing = null)
                .hasScanWarning shouldBe false
        }

        test("toBookEntity carries bookTierLabel and partTierLabel from payload") {
            val payload = bookPayload().copy(bookTierLabel = "Book", partTierLabel = "Part")
            val result = mapper.toBookEntity(payload, existing = null)

            result.bookTierLabel shouldBe "Book"
            result.partTierLabel shouldBe "Part"
        }

        test("toBookEntity leaves bookTierLabel and partTierLabel null when payload doesn't name the tiers") {
            val payload = bookPayload()
            val result = mapper.toBookEntity(payload, existing = null)

            result.bookTierLabel.shouldBeNull()
            result.partTierLabel.shouldBeNull()
        }

        // --- toDetail mapping ---

        fun bookWithContributors(
            hasScanWarning: Boolean = false,
            coverDownloadedAt: Timestamp? = null,
        ): BookWithContributors =
            BookWithContributors(
                book =
                    bookEntity().copy(
                        revision = 1L,
                        hasScanWarning = hasScanWarning,
                        coverDownloadedAt = coverDownloadedAt,
                    ),
                contributors = emptyList(),
                contributorRoles = emptyList(),
                series = emptyList(),
                seriesSequences = emptyList(),
            )

        test("toDetail carries hasScanWarning from the book entity — true and false") {
            // Stat-elimination regression guard: no `exists` stub — a strict Mokkery mock throws
            // on any unstubbed call, so if toDetail ever regresses to calling
            // ImageStorage.exists() again, this test fails.
            val imageStorage = mock<ImageStorage> { every { getCoverPath(any()) } returns "/covers/book-1.jpg" }

            bookWithContributors(hasScanWarning = true)
                .toDetail(imageStorage, genres = emptyList(), tags = emptyList(), moods = emptyList())
                .hasScanWarning shouldBe true
            bookWithContributors(hasScanWarning = false)
                .toDetail(imageStorage, genres = emptyList(), tags = emptyList(), moods = emptyList())
                .hasScanWarning shouldBe false
        }

        test("toListItem derives coverPath from coverDownloadedAt — pure string construction, no stat") {
            // Stat-elimination regression guard: no `exists` stub — a strict Mokkery mock throws
            // on any unstubbed call, so if toListItem ever regresses to calling
            // ImageStorage.exists() again, this test fails.
            val imageStorage = mock<ImageStorage> { every { getCoverPath(any()) } returns "/covers/book-1.jpg" }

            bookWithContributors(coverDownloadedAt = null).toListItem(imageStorage).coverPath.shouldBeNull()
            bookWithContributors(coverDownloadedAt = Timestamp(ENTITY_UPDATED_AT_MS))
                .toListItem(imageStorage)
                .coverPath shouldBe "/covers/book-1.jpg"
        }

        test("toDetail derives coverPath from coverDownloadedAt — pure string construction, no stat") {
            // Stat-elimination regression guard: no `exists` stub — a strict Mokkery mock throws
            // on any unstubbed call, so if toDetail ever regresses to calling
            // ImageStorage.exists() again, this test fails.
            val imageStorage = mock<ImageStorage> { every { getCoverPath(any()) } returns "/covers/book-1.jpg" }

            bookWithContributors(coverDownloadedAt = null)
                .toDetail(imageStorage, genres = emptyList(), tags = emptyList(), moods = emptyList())
                .coverPath
                .shouldBeNull()
            bookWithContributors(coverDownloadedAt = Timestamp(ENTITY_UPDATED_AT_MS))
                .toDetail(imageStorage, genres = emptyList(), tags = emptyList(), moods = emptyList())
                .coverPath shouldBe "/covers/book-1.jpg"
        }

        test("toAudioFile carries the audio-stream fields") {
            val entity =
                AudioFileEntity(
                    bookId = BookId("b1"),
                    index = 0,
                    id = "af1",
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "ac4",
                    duration = 100L,
                    size = 1024L,
                    codecProfile = null,
                    spatial = "atmos",
                    bitrate = 320_000,
                    sampleRate = 48_000,
                    channels = 2,
                )

            val domain = entity.toAudioFile()

            domain.codec shouldBe "ac4"
            domain.spatial shouldBe "atmos"
            domain.bitrate shouldBe 320_000
            domain.sampleRate shouldBe 48_000
            domain.channels shouldBe 2
            domain.codecProfile shouldBe null
        }
    })
