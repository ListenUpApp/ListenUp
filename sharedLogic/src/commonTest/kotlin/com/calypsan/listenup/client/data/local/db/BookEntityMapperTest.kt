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

// Palette color ARGB constants reused across multiple test cases.
private const val COLOR_DOMINANT = 0xFF2244CC.toInt()
private const val COLOR_DARK_MUTED = 0xFF112233.toInt()
private const val COLOR_VIBRANT = 0xFF3366FF.toInt()

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
 * - Client-computed fields (palette colors, blur hash) are preserved from an
 *   existing [BookEntity] row and never overwritten with null on sync.
 * - When [existing] is null (first-seen book), those fields default to null.
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
            dominantColor: Int? = COLOR_DOMINANT,
            darkMutedColor: Int? = COLOR_DARK_MUTED,
            vibrantColor: Int? = COLOR_VIBRANT,
            coverBlurHash: String? = "L5H2EC=PM+yV",
        ): BookEntity =
            BookEntity(
                id = id,
                libraryId = LibraryId("test-library"),
                folderId = FolderId("test-folder"),
                title = "Old Title",
                totalDuration = 1_000L,
                dominantColor = dominantColor,
                darkMutedColor = darkMutedColor,
                vibrantColor = vibrantColor,
                coverBlurHash = coverBlurHash,
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

        test("toBookEntity with existing null sets all client-computed fields to null") {
            val payload = bookPayload()
            val result = mapper.toBookEntity(payload, existing = null)

            result.dominantColor.shouldBeNull()
            result.darkMutedColor.shouldBeNull()
            result.vibrantColor.shouldBeNull()
            result.coverBlurHash.shouldBeNull()
        }

        test("toBookEntity preserves all palette colors and coverBlurHash from existing row") {
            val existing =
                bookEntity(
                    dominantColor = COLOR_DOMINANT,
                    darkMutedColor = COLOR_DARK_MUTED,
                    vibrantColor = COLOR_VIBRANT,
                    coverBlurHash = "L5H2EC=PM+yV",
                )
            val payload = bookPayload()
            val result = mapper.toBookEntity(payload, existing = existing)

            result.dominantColor shouldBe COLOR_DOMINANT
            result.darkMutedColor shouldBe COLOR_DARK_MUTED
            result.vibrantColor shouldBe COLOR_VIBRANT
            result.coverBlurHash shouldBe "L5H2EC=PM+yV"
        }

        test("toBookEntity does not carry over other fields from existing row — wire payload wins") {
            // The existing row has a stale title; the wire payload carries the new one.
            val existing = bookEntity() // title = "Old Title"
            val payload = bookPayload(title = "New Title from Server")
            val result = mapper.toBookEntity(payload, existing = existing)

            result.title shouldBe "New Title from Server"
        }

        test("toBookEntity carries hasScanWarning from payload — true and false") {
            mapper
                .toBookEntity(bookPayload().copy(hasScanWarning = true), existing = null)
                .hasScanWarning shouldBe true
            mapper
                .toBookEntity(bookPayload().copy(hasScanWarning = false), existing = null)
                .hasScanWarning shouldBe false
        }

        // --- toDetail mapping ---

        fun bookWithContributors(hasScanWarning: Boolean): BookWithContributors =
            BookWithContributors(
                book =
                    bookEntity().copy(
                        revision = 1L,
                        hasScanWarning = hasScanWarning,
                    ),
                contributors = emptyList(),
                contributorRoles = emptyList(),
                series = emptyList(),
                seriesSequences = emptyList(),
            )

        test("toDetail carries hasScanWarning from the book entity — true and false") {
            val imageStorage = mock<ImageStorage> { every { exists(any()) } returns false }

            bookWithContributors(hasScanWarning = true)
                .toDetail(imageStorage, genres = emptyList(), tags = emptyList())
                .hasScanWarning shouldBe true
            bookWithContributors(hasScanWarning = false)
                .toDetail(imageStorage, genres = emptyList(), tags = emptyList())
                .hasScanWarning shouldBe false
        }
    })
