@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.api.BookServiceImpl
import com.calypsan.listenup.server.cover.PendingCover
import com.calypsan.listenup.server.db.sqldelight.Books
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Every book write path must leave the columns it has no business changing exactly as it found
 * them. The book aggregate protects those columns in four separate ways (sticky UPLOADED covers,
 * sticky USER chapters, per-field provenance, preserve flags), and three regressions of this class
 * shipped in one week — each a write path that re-read the aggregate and wrote back a column the
 * wire payload could not carry, or carried stale.
 *
 * One table of paths, one assertion: a new write path is added here first, then written. Each
 * path names the columns it may legitimately change; every other column must be byte-identical.
 */
class BookRepositoryServerPrivateColumnsTest :
    FunSpec({

        writePaths.forEach { path ->
            test("${path.label} leaves every column it does not own byte-identical") {
                withSqlDatabase {
                    val db = this
                    sql.seedTestLibraryAndFolder()
                    withCoverStore { coverStore, homeDir ->
                        val (service, repo) = makeBookServiceAndRepo(db, coverStore, homeDir)
                        runTest {
                            val bookId = seedFullyDressedBook(repo, db)
                            val seeded = db.guardedColumns(bookId)
                            // The seed is itself a chain of writes: if one of them already dropped a
                            // dressed column, "before equals after" would pass trivially over nulls.
                            DRESSED_COLUMNS.filter { seeded[it] == null } shouldBe emptyList()
                            val before = seeded - path.mayChange

                            path.apply(WriteContext(service, repo, db, bookId))

                            db.guardedColumns(bookId) - path.mayChange shouldBe before
                        }
                    }
                }
            }
        }
    })

private data class WriteContext(
    val service: BookServiceImpl,
    val repo: BookRepository,
    val db: SqlTestDatabases,
    val bookId: BookId,
)

private data class WritePath(
    val label: String,
    val mayChange: Set<String> = emptySet(),
    val apply: suspend WriteContext.() -> Unit,
)

private const val SEEDED_ROOT = "Author/EMBEDDED"

/** The guarded columns [seedFullyDressedBook] sets to a non-null value. */
private val DRESSED_COLUMNS =
    listOf(
        "cover_source",
        "cover_path",
        "cover_hash",
        "book_tier_label",
        "part_tier_label",
        "description",
        "root_rel_path",
    )

private val writePaths =
    listOf(
        WritePath("a plain re-upsert of the re-read aggregate") {
            repo.upsert(repo.findById(bookId) ?: error("missing")).shouldBeInstanceOf<AppResult.Success<*>>()
        },
        WritePath("a title edit through BookServiceImpl", mayChange = setOf("field_provenance")) {
            service.updateBook(bookId, BookUpdate(title = "Edited")).shouldBeInstanceOf<AppResult.Success<Unit>>()
        },
        WritePath("a match-apply genre write (setBookGenres, then re-upsert)") {
            repo.setBookGenres(bookId, listOf("Horror")).shouldBeInstanceOf<AppResult.Success<Unit>>()
            repo.upsert(repo.findById(bookId) ?: error("missing")).shouldBeInstanceOf<AppResult.Success<*>>()
        },
        WritePath("a revision touch") {
            repo.touchRevision(bookId).shouldBeInstanceOf<AppResult.Success<Unit>>()
        },
        WritePath("an identical rescan") {
            rescan(genres = emptyList())
        },
        WritePath("a rescan whose only change is genres", mayChange = setOf("scanned_at")) {
            rescan(genres = listOf("Horror"))
        },
    )

/** Re-scans the seeded book's folder with the same files and cover bytes, and [genres]. */
private suspend fun WriteContext.rescan(genres: List<String>) {
    val libraryId = LibraryRegistry(db.sql).currentLibrary()
    val analyzed = coverPathAnalyzedBook(SEEDED_ROOT).copy(genres = genres)
    val pending = PendingCover(bytes = fakeCoverBytes(), mime = "image/jpeg", source = CoverSource.EMBEDDED)
    repo
        .resolveOrInsert(libraryId, FolderId("test-folder"), analyzed, pending)
        .shouldBeInstanceOf<AppResult.Success<IngestOutcome>>()
}

/**
 * A scanned book with a managed embedded cover, then the guarded columns a user can set moved off
 * their defaults — tier labels and a curated description — so a write that resets one cannot pass
 * by coincidence. (`normalization_gain_db` is left as scanned: the files are its only source, so a
 * rescan re-deriving it is correct, not a regression.)
 */
private suspend fun seedFullyDressedBook(
    repo: BookRepository,
    db: SqlTestDatabases,
): BookId {
    val (bookId, _) = seedManagedCover(repo, db, CoverSource.EMBEDDED)
    repo.setTierLabels(bookId, bookTierLabel = "Volume", partTierLabel = "Part").shouldBeInstanceOf<AppResult.Success<Unit>>()
    val seeded = repo.findById(bookId) ?: error("missing")
    repo
        .upsert(
            seeded.copy(
                description = "A curated description.",
                fieldProvenance =
                    seeded.fieldProvenance +
                        (BookField.DESCRIPTION to FieldProvenance(FieldSourceKind.USER, at = 1L)),
            ),
        ).shouldBeInstanceOf<AppResult.Success<*>>()
    return bookId
}

/** The columns no edit of a book's text or genres should ever move, keyed by column name. */
private fun SqlTestDatabases.guardedColumns(bookId: BookId): Map<String, Any?> =
    sql.booksQueries
        .selectById(bookId.value)
        .executeAsOne()
        .guarded()

private fun Books.guarded(): Map<String, Any?> =
    mapOf(
        "cover_source" to cover_source,
        "cover_path" to cover_path,
        "cover_hash" to cover_hash,
        "chapter_source" to chapter_source,
        "book_tier_label" to book_tier_label,
        "part_tier_label" to part_tier_label,
        "field_provenance" to field_provenance,
        "normalization_gain_db" to normalization_gain_db,
        "description" to description,
        "root_rel_path" to root_rel_path,
        "inode" to inode,
        "scanned_at" to scanned_at,
        "created_at" to created_at,
    )
