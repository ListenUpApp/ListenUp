package com.calypsan.listenup.server.upload

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.domain.embeddedmeta.Chapter
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

/**
 * The **chapter-fingerprint** tier of duplicate detection, in isolation.
 *
 * The end-to-end upload tests exercise the ASIN and title/author tiers naturally, but they use
 * placeholder audio the parser cannot read, so no chapters ever reach the middle tier there. That
 * tier is the one that catches the interesting real case — the same book, re-downloaded from a
 * different store, carrying no ASIN and a differently-typed title — so it earns a direct test.
 */
class UploadDuplicateDetectorTest :
    FunSpec({

        val libraryId = LibraryId("test-library")

        fun analyzed(
            title: String,
            author: String? = null,
            asin: String? = null,
            chapters: List<Chapter> = emptyList(),
        ) = AnalyzedBook(
            candidate = CandidateBook(rootRelPath = "staged", isFile = false, files = emptyList()),
            title = title,
            authors = listOfNotNull(author),
            asin = asin,
            chapters = chapters,
        )

        test("a book whose chapter titles and near-identical lengths match an existing one is a duplicate") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val existing = sql.seedBookWithChapters(title = "Some Other Spelling", chapters = CHAPTERS)

                // Same chapters, but each 900 ms longer — a re-encode, well inside the 5s bucket.
                val reencoded =
                    CHAPTERS.mapIndexed { index, (chapterTitle, durationMs) ->
                        Chapter(
                            index = index + 1,
                            title = chapterTitle,
                            startMs = 0L,
                            endMs = durationMs + 900L,
                        )
                    }

                val match =
                    runBlocking {
                        UploadDuplicateDetector(sql)
                            .findExisting(libraryId, analyzed(title = "A Totally Different Title", chapters = reencoded))
                    }

                match.shouldNotBeNull().bookId shouldBe existing
                match.title shouldBe "Some Other Spelling"
            }
        }

        test("a book with different chapters is not a duplicate") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedBookWithChapters(title = "Some Other Spelling", chapters = CHAPTERS)

                val different =
                    listOf(
                        Chapter(index = 1, title = "Prologue", startMs = 0L, endMs = 60_000L),
                        Chapter(index = 2, title = "Chapter One", startMs = 0L, endMs = 999_000L),
                    )

                runBlocking {
                    UploadDuplicateDetector(sql)
                        .findExisting(libraryId, analyzed(title = "Unrelated", chapters = different))
                }.shouldBeNull()
            }
        }

        test("an ASIN match wins before chapters are ever consulted") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val existing = sql.seedBookWithChapters(title = "By ASIN", chapters = emptyList(), asin = "B00TESTASIN")

                runBlocking {
                    UploadDuplicateDetector(sql).findExisting(libraryId, analyzed(title = "Whatever", asin = "B00TESTASIN"))
                }.shouldNotBeNull().bookId shouldBe existing
            }
        }

        test("a tombstoned book is never matched — a deleted book must not block a re-upload") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val existing = sql.seedBookWithChapters(title = "Deleted", chapters = CHAPTERS, asin = "B00GONEXXXX")
                sql.transaction {
                    sql.booksQueries.softDeleteById(
                        revision = 2L,
                        updated_at = 1L,
                        deleted_at = 1L,
                        client_op_id = null,
                        id = existing,
                    )
                }

                runBlocking {
                    UploadDuplicateDetector(sql).findExisting(libraryId, analyzed(title = "Deleted", asin = "B00GONEXXXX"))
                }.shouldBeNull()
            }
        }
    })

/** Two chapters whose durations sit comfortably inside distinct 5-second fingerprint buckets. */
private val CHAPTERS =
    listOf(
        "Prologue" to 120_000L,
        "Chapter One" to 1_800_000L,
    )

/** Inserts a live book (plus its chapter rows) directly — the fixture the fingerprint tier reads. */
private fun ListenUpDatabase.seedBookWithChapters(
    title: String,
    chapters: List<Pair<String, Long>>,
    asin: String? = null,
): String {
    val bookId = Uuid.random().toString()
    transaction {
        booksQueries.insert(
            id = bookId,
            library_id = "test-library",
            folder_id = "test-folder",
            title = title,
            sort_title = title,
            subtitle = null,
            description = null,
            publish_year = null,
            normalization_gain_db = null,
            publisher = null,
            language = null,
            isbn = null,
            asin = asin,
            abridged = 0L,
            explicit = 0L,
            has_scan_warning = 0L,
            total_duration = 0L,
            cover_source = null,
            cover_path = null,
            cover_hash = null,
            field_provenance = "{}",
            root_rel_path = "seed/$bookId",
            inode = null,
            scanned_at = 1L,
            book_tier_label = null,
            part_tier_label = null,
            revision = 1L,
            created_at = 1L,
            updated_at = 1L,
            deleted_at = null,
            client_op_id = null,
        )
        chapters.forEachIndexed { index, (chapterTitle, durationMs) ->
            bookChaptersQueries.insert(
                book_id = bookId,
                ordinal = index.toLong(),
                id = Uuid.random().toString(),
                title = chapterTitle,
                duration = durationMs,
                start_time = 0L,
                part_title = null,
                book_title = null,
            )
        }
    }
    return bookId
}
