@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Characterizes [BookRepository.resolveOrInsertAll]'s per-book write isolation (#scanner-chunk-
 * rollback) — the fix for the bug where [BookRepository.writeChunk] wrapped every book in a chunk
 * inside ONE outer [com.calypsan.listenup.server.db.sqldelight.suspendTransaction], each book
 * running in a nested `db.transactionWithResult { }`. SQLDelight 2.3.2 does NOT implement nested
 * transactions as real SQLite SAVEPOINTs. Read against the actual runtime source
 * (`app.cash.sqldelight:runtime:2.3.2`, `Transacter.kt`'s `postTransactionCleanup`), a nested
 * transaction's cleanup runs `enclosing.childrenSuccessful = transaction.successful &&
 * transaction.childrenSuccessful` — an ASSIGNMENT, not an accumulating AND. So the enclosing
 * (outer) transaction's `childrenSuccessful` flag reflects only the MOST RECENT nested child's
 * outcome, not "were there any failures". A poisoned book followed by a later, successful book in
 * the same chunk has its damage silently overwritten back to `true` — the outer transaction still
 * rolls back only if the LAST nested transaction it ran (or, more precisely, whichever ran most
 * recently at the time the outer's own `endTransaction()` fires) failed. This suite's poison book
 * is therefore positioned LAST in the persisted list — empirically verified: with the poison book
 * in the MIDDLE, the pre-fix code (surprisingly) let both siblings commit; with it LAST, the whole
 * chunk rolled back (`countLive() == 0`), reproducing the bug the plan describes. Both orderings
 * are real production bugs (a bad book anywhere in a chunk can, depending on what runs after it,
 * either survive by luck or take out the whole chunk) — this test pins the deterministic-failure
 * ordering so the RED/GREEN transition is reliable.
 *
 * [BookRepositoryBatchedPersistTest]'s existing "one book that throws on write does not drop the
 * others in its chunk" test gives FALSE confidence: its poison book carries an absolute
 * `rootRelPath`, which [BookRepository.prepareBooks] rejects BEFORE any write transaction opens —
 * it never reaches [BookRepository.writeChunk]'s nested-transaction body, so it can't exercise the
 * rollback bug. This suite forces a throw genuinely INSIDE the write: two [AnalyzedBook]s sharing
 * the same `rootRelPath` both survive [BookRepository.prepareBooks] (neither is in the DB yet, so
 * both resolve as fresh inserts with distinct minted ids), but only the FIRST to reach
 * `db.booksQueries.insert` can claim the natural key — `idx_book_natural_key` (a UNIQUE index on
 * `(folder_id, root_rel_path)`, see `Books.sq`) rejects the second with a genuine SQLite constraint
 * violation raised from inside [com.calypsan.listenup.server.sync.SqlSyncableRepository]'s
 * `upsertInOpenTransaction`/`writePayload` — the real write body, not a prepare-phase `require()`.
 */
class BookRepositoryChunkIsolationTest :
    FunSpec({

        test("a book that throws inside the write does not roll back its siblings' commits") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = newChunkIsolationRepo()
                runTest {
                    // b1 and the poison duplicate share the SAME rootRelPath. Neither exists in the DB
                    // yet, so BOTH survive prepareBooks as fresh inserts with distinct minted ids — the
                    // collision is only detectable once the second one's INSERT actually runs against
                    // the natural-key UNIQUE index, i.e. genuinely inside the write. The poison book is
                    // LAST in the list — see the class KDoc for why position matters pre-fix.
                    val result =
                        repo.persistChunkIsolationBooks(
                            listOf(
                                book("books/b1", title = "First"),
                                book("books/b3", title = "Third"),
                                book("books/b1", title = "PoisonDuplicate"),
                            ),
                        )

                    // Both siblings committed; only the colliding duplicate failed.
                    result.persisted shouldBe 2
                    result.failed shouldBe 1

                    // TODAY (pre-fix): the poison book is last, so its nested-transaction failure is
                    // the last word on the outer transaction's childrenSuccessful flag — the whole
                    // chunk rolls back and this is 0 (verified empirically), disproving the "savepoint"
                    // comment. AFTER the fix, each book commits independently, so this is 2.
                    sql.booksQueries.countLive().executeAsOne() shouldBe 2L

                    // b1 survives with its OWN content — the duplicate never overwrote it, because it
                    // never committed at all (a failed INSERT, not a successful UPDATE).
                    val b1 = repo.findById(BookId(sql.idOf("books/b1"))).shouldNotBeNull()
                    b1.title shouldBe "First"

                    // b3 — the sibling written AFTER the poisoned book — also survives.
                    repo.findById(BookId(sql.idOf("books/b3"))).shouldNotBeNull()

                    // Exactly one live row claims the "books/b1" natural key — the duplicate is truly
                    // gone, not merely shadowed.
                    sql.booksQueries
                        .selectIdsByPaths(TEST_CHUNK_ISOLATION_FOLDER_ID.value, listOf("books/b1"))
                        .executeAsList() shouldHaveSize 1
                }
            }
        }
    })

// --- Constants ----------------------------------------------------------------

private val TEST_CHUNK_ISOLATION_LIBRARY_ID = LibraryId("test-library")
private val TEST_CHUNK_ISOLATION_FOLDER_ID = FolderId("test-folder")

// --- Driver ---------------------------------------------------------------------

/**
 * Drives [books] through [BookRepository.resolveOrInsertAll], pre-resolving identities the same
 * way the scan orchestrator does. Mirrors
 * [BookRepositoryBatchedPersistTest]'s private `persistAllBooks` helper (kept separate per-file
 * rather than shared, matching that file's own note that it is not to be modified).
 */
private suspend fun BookRepository.persistChunkIsolationBooks(books: List<AnalyzedBook>): PersistResult {
    val identityMaps = resolveScanIdentities(books)
    return resolveOrInsertAll(
        libraryId = TEST_CHUNK_ISOLATION_LIBRARY_ID,
        folderId = TEST_CHUNK_ISOLATION_FOLDER_ID,
        books = books,
        coversByBook = emptyMap(),
        systemCollectionId = null,
        identityMaps = identityMaps,
        onProgress = { _, _ -> },
    )
}

/** Resolves the persisted book id for [rootRelPath] via the folder-scoped natural-key lookup. */
private fun ListenUpDatabase.idOf(rootRelPath: String): String {
    val query = booksQueries.selectIdByNaturalKey(TEST_CHUNK_ISOLATION_FOLDER_ID.value, rootRelPath)
    return query.executeAsOne()
}

// --- Fixtures ---------------------------------------------------------------

private fun com.calypsan.listenup.server.testing.SqlTestDatabases.newChunkIsolationRepo(): BookRepository {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = sql,
        driver = driver,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(sql, bus, syncRegistry),
        seriesRepository = SeriesRepository(sql, bus, syncRegistry),
        genreRepository = GenreRepository(sql, bus, syncRegistry),
    )
}

/** Copied from [BookRepositoryBatchedPersistTest]'s private `book(...)` factory (not shared). */
private fun book(
    rootRelPath: String,
    title: String = rootRelPath.substringAfterLast('/'),
    authors: List<String> = emptyList(),
    narrators: List<String> = emptyList(),
    series: List<SeriesEntry> = emptyList(),
    genres: List<String> = emptyList(),
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = null,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate =
            CandidateBook(
                rootRelPath = rootRelPath,
                isFile = false,
                files = listOf(file),
            ),
        title = title,
        authors = authors,
        narrators = narrators,
        series = series,
        genres = genres,
        tracks = listOf(TrackEntry(file = file)),
    )
}
