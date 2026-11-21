@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Data-integrity regression coverage for finding A3: a transient Removed→re-Added cycle (the
 * amplifier that turns an A1/A2 flicker into durable loss).
 *
 * `softDelete` cascade-tombstones a book's `book_tags` / `book_moods` / `collection_books` junctions.
 * A scan re-ingest revives the book ROW (`updateContent` sets `deleted_at = NULL`) but historically
 * left those junctions tombstoned — so the book came back UNCOLLECTED (invisible under the pure-union
 * visibility rule) with the user's tags and moods silently gone. Both scan revival paths
 * (`resolveOrInsert` single, `resolveOrInsertAll` batch) must run the same junction-revival cascade
 * that `reviveByIds` runs, floored at the book's own `deleted_at`.
 */
class BookRepositoryScanRevivalCascadeTest :
    FunSpec({

        test("re-ingesting a tombstoned book via the scan single path revives its tags, moods, and collection membership") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                seedCollection(sql)
                runTest {
                    val h = harness(sql, driver)
                    val libId = LibraryId("test-library")
                    val analyzed = analyzedFor("Author/Book", inode = 42L)

                    // Ingest, then attach a tag + mood + collection membership.
                    val bookId = h.repo.resolveOrInsert(libId, FOLDER, analyzed).bookId()
                    h.attachJunctions(bookId)

                    // Transient removal (tombstones the book + cascades its junctions).
                    h.repo.softDelete(bookId, clientOpId = null)
                    h.assertJunctionsDead()

                    // Re-ingest the SAME book via the scan single path — the row revives.
                    h.repo.resolveOrInsert(libId, FOLDER, analyzed).bookId()

                    // The junctions must return with it — not be left tombstoned.
                    h.assertJunctionsLive(bookId)
                }
            }
        }

        test("re-ingesting a tombstoned book via the scan BATCH path revives its tags, moods, and collection membership") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                seedCollection(sql)
                runTest {
                    val h = harness(sql, driver)
                    val libId = LibraryId("test-library")
                    val analyzed = analyzedFor("Author/Batch Book", inode = 77L)

                    val insert =
                        h.repo.resolveOrInsertAll(
                            libraryId = libId,
                            folderId = FOLDER,
                            books = listOf(analyzed),
                            coversByBook = emptyMap(),
                            systemCollectionId = null,
                            identityMaps = ScanIdentityMaps(),
                        ) { _, _ -> }
                    val bookId = insert.resolvedIds.single()
                    h.attachJunctions(bookId)

                    h.repo.softDelete(bookId, clientOpId = null)
                    h.assertJunctionsDead()

                    // Re-ingest via the production scan path (BookPersister uses resolveOrInsertAll).
                    h.repo.resolveOrInsertAll(
                        libraryId = libId,
                        folderId = FOLDER,
                        books = listOf(analyzed),
                        coversByBook = emptyMap(),
                        systemCollectionId = null,
                        identityMaps = ScanIdentityMaps(),
                    ) { _, _ -> }

                    h.assertJunctionsLive(bookId)
                }
            }
        }

        // A full scan's sweep runs under FirehoseSuppressed so a vanished folder of hundreds of books cannot flood the
        // 256-slot live tail. The book row honours the marker; its junction cascade must too.
        test("a removal under FirehoseSuppressed tombstones the junctions but publishes none of them") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                seedCollection(sql)
                runTest {
                    val h = harness(sql, driver)
                    val bookId = h.repo.resolveOrInsert(LIBRARY, FOLDER, analyzedFor("Author/Quiet", inode = 5L)).bookId()
                    h.attachJunctions(bookId)
                    val before = h.mark()

                    withContext(FirehoseSuppressed) { h.repo.softDelete(bookId, clientOpId = null) }

                    h.assertJunctionsDead()
                    h.junctionTombstonesAbove(bookId, before.revision) shouldBe 3
                    h.junctionEventIdsSince(before, bookId).shouldBeEmpty()
                }
            }
        }

        test("a removal without the marker still publishes a tombstone per junction row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                seedCollection(sql)
                runTest {
                    val h = harness(sql, driver)
                    val bookId = h.repo.resolveOrInsert(LIBRARY, FOLDER, analyzedFor("Author/Loud", inode = 6L)).bookId()
                    h.attachJunctions(bookId)
                    val before = h.mark()

                    h.repo.softDelete(bookId, clientOpId = null)

                    h.junctionEventIdsSince(before, bookId) shouldContainExactlyInAnyOrder h.junctionIds(bookId)
                }
            }
        }

        test("a scan revival under FirehoseSuppressed revives the junctions but publishes none of them") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                seedCollection(sql)
                runTest {
                    val h = harness(sql, driver)
                    val analyzed = analyzedFor("Author/Back Again", inode = 7L)
                    val bookId = h.repo.resolveOrInsert(LIBRARY, FOLDER, analyzed).bookId()
                    h.attachJunctions(bookId)
                    h.repo.softDelete(bookId, clientOpId = null)
                    val before = h.mark()

                    withContext(FirehoseSuppressed) { h.repo.resolveOrInsert(LIBRARY, FOLDER, analyzed).bookId() }

                    h.assertJunctionsLive(bookId)
                    h.junctionEventIdsSince(before, bookId).shouldBeEmpty()
                }
            }
        }
    })

private val LIBRARY = LibraryId("test-library")

/** Where the live tail stood before an action: how many events it held, and the highest junction revision committed. */
private data class TailMark(
    val eventCount: Int,
    val revision: Long,
)

private val FOLDER = FolderId("test-folder")

/** One page comfortably holds every junction row these specs write. */
private const val PAGE = 100

private fun AppResult<IngestOutcome>.bookId(): BookId =
    when (this) {
        is AppResult.Success -> data.bookId
        is AppResult.Failure -> error("resolveOrInsert failed: ${error.message}")
    }

/** Seeds a NORMAL collection `c1` in `test-library` so a membership can be attached. */
private fun seedCollection(sql: ListenUpDatabase) {
    val now = System.currentTimeMillis()
    sql.collectionsQueries.insert(
        id = "c1",
        library_id = "test-library",
        owner_id = "owner1",
        name = "Faves",
        type = "NORMAL",
        created_at = now,
        updated_at = now,
        revision = 0L,
        deleted_at = null,
        client_op_id = null,
    )
}

private class RevivalHarness(
    val bus: ChangeBus,
    val repo: BookRepository,
    val tagRepo: TagRepository,
    val bookTagRepo: BookTagRepository,
    val moodRepo: MoodRepository,
    val bookMoodRepo: BookMoodRepository,
    val collectionBookRepo: CollectionBookRepository,
) {
    suspend fun attachJunctions(bookId: BookId) {
        val id = bookId.value
        tagRepo.upsert(Tag(id = "t1", name = "Sci-Fi", slug = "sci-fi", revision = 0, updatedAt = 0))
        bookTagRepo.upsert(BookTagSyncPayload(id = "$id:t1", bookId = id, tagId = "t1", createdAt = 1000L, revision = 0L))
        moodRepo.upsert(Mood(id = "m1", name = "Tense", slug = "tense", revision = 0, updatedAt = 0))
        bookMoodRepo.upsert(BookMoodSyncPayload(id = "$id:m1", bookId = id, moodId = "m1", createdAt = 1000L, revision = 0L))
        collectionBookRepo.upsert(
            CollectionBookSyncPayload(id = "c1:$id", collectionId = "c1", bookId = id, createdAt = 1000L, revision = 0L),
        )
        check(bookTagRepo.findAllForBook(id).size == 1)
        check(bookMoodRepo.findAllForBook(id).size == 1)
        check(collectionBookRepo.findBookIdsForCollection("c1") == listOf(id))
    }

    fun junctionIds(bookId: BookId): List<String> = listOf("${bookId.value}:t1", "${bookId.value}:m1", "c1:${bookId.value}")

    suspend fun mark(): TailMark {
        val revisions =
            bookTagRepo.pullSince(userId = null, cursor = 0L, limit = PAGE).items.map { it.revision } +
                bookMoodRepo.pullSince(userId = null, cursor = 0L, limit = PAGE).items.map { it.revision } +
                collectionBookRepo.pullSince(userId = null, cursor = 0L, limit = PAGE).items.map { it.revision }
        return TailMark(eventCount = bus.subscribe().replayCache.size, revision = revisions.maxOrNull() ?: 0L)
    }

    /** The junction ids of [bookId] among the events the live tail received after [mark]. */
    fun junctionEventIdsSince(
        mark: TailMark,
        bookId: BookId,
    ): List<String> {
        val ids = junctionIds(bookId).toSet()
        return bus
            .subscribe()
            .replayCache
            .drop(mark.eventCount)
            .map { it.event.id }
            .filter { it in ids }
    }

    /** How many of [bookId]'s junction rows REST catch-up sees tombstoned above [revision]. */
    suspend fun junctionTombstonesAbove(
        bookId: BookId,
        revision: Long,
    ): Int {
        val ids = junctionIds(bookId).toSet()
        val tombstoned =
            bookTagRepo
                .pullSince(userId = null, cursor = revision, limit = PAGE)
                .items
                .filter { it.deletedAt != null }
                .map { it.id } +
                bookMoodRepo
                    .pullSince(userId = null, cursor = revision, limit = PAGE)
                    .items
                    .filter { it.deletedAt != null }
                    .map { it.id } +
                collectionBookRepo
                    .pullSince(userId = null, cursor = revision, limit = PAGE)
                    .items
                    .filter { it.deletedAt != null }
                    .map { it.id }
        return tombstoned.count { it in ids }
    }

    suspend fun assertJunctionsDead() {
        // After softDelete's cascade, live-row reads return nothing.
        collectionBookRepo.findBookIdsForCollection("c1").shouldBeEmpty()
    }

    suspend fun assertJunctionsLive(bookId: BookId) {
        val id = bookId.value
        bookTagRepo.findAllForBook(id).shouldHaveSize(1)
        bookMoodRepo.findAllForBook(id).shouldHaveSize(1)
        collectionBookRepo.findBookIdsForCollection("c1").shouldContainExactly(id)
    }
}

private fun harness(
    sql: ListenUpDatabase,
    driver: app.cash.sqldelight.db.SqlDriver,
): RevivalHarness {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val tagRepo = TagRepository(db = sql, bus = bus, registry = syncRegistry)
    val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)
    val moodRepo = MoodRepository(db = sql, bus = bus, registry = syncRegistry)
    val bookMoodRepo = BookMoodRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)
    val collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)
    val repo =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = ContributorRepository(sql, bus, syncRegistry),
            seriesRepository = SeriesRepository(sql, bus, syncRegistry),
            genreRepository = GenreRepository(sql, bus, syncRegistry),
            collectionBookRepository = collectionBookRepo,
            tagRepository = tagRepo,
            bookTagRepository = bookTagRepo,
            bookMoodRepository = bookMoodRepo,
        )
    return RevivalHarness(bus, repo, tagRepo, bookTagRepo, moodRepo, bookMoodRepo, collectionBookRepo)
}

private fun analyzedFor(
    rootRelPath: String,
    inode: Long?,
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = inode,
            fileType = FileType.AUDIO,
        )
    val candidate = CandidateBook(rootRelPath = rootRelPath, isFile = false, files = listOf(file))
    return AnalyzedBook(
        candidate = candidate,
        title = rootRelPath.substringAfterLast('/'),
        tracks = listOf(TrackEntry(file = file)),
    )
}
