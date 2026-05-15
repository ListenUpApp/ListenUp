@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.db.BookSearchMapTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class BookRepositoryUpsertTest :
    FunSpec({

        test("upsert of fresh book inserts row + all children atomically; emits SyncEvent.Created") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val bus = ChangeBus()
                val repo = BookRepository(db = db, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    val payload =
                        bookPayloadFixture(
                            id = "b1",
                            title = "Way of Kings",
                            contributors =
                                listOf(
                                    contributor("c1", "Brandon Sanderson", "author"),
                                    contributor("c2", "Michael Kramer", "narrator"),
                                ),
                            series = listOf(series("s1", "Stormlight Archive", "1")),
                            chapters =
                                listOf(
                                    chapter("ch1", "Prologue", 1_200_000L, 0L),
                                    chapter("ch2", "Chapter 1", 1_800_000L, 1_200_000L),
                                ),
                            audioFiles = listOf(audioFile("af1", "01.m4b", 162_000_000L, 200_000_000L)),
                            cover = CoverPayload(source = CoverSource.FILESYSTEM, hash = "deadbeef"),
                        )

                    val result = repo.upsert(payload, clientOpId = "op-1")

                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    val saved = result.data
                    saved.id shouldBe "b1"
                    saved.title shouldBe "Way of Kings"
                    saved.contributors.size shouldBe 2
                    saved.series.size shouldBe 1
                    saved.chapters.size shouldBe 2
                    saved.audioFiles.size shouldBe 1
                    saved.revision shouldBe 1L
                    saved.cover?.hash shouldBe "deadbeef"

                    val busEvent = deferred.await()
                    busEvent.repo.domainName shouldBe "books"
                    val event = busEvent.event
                    event.shouldBeInstanceOf<SyncEvent.Created<BookSyncPayload>>()
                    event.id shouldBe "b1"
                    event.clientOpId shouldBe "op-1"
                }
            }
        }

        test("upsert replaces child rows wholesale on second call") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val repo = BookRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val v1 =
                        bookPayloadFixture(
                            id = "b1",
                            title = "Way of Kings",
                            contributors =
                                listOf(
                                    contributor("c1", "Brandon Sanderson", "author"),
                                    contributor("c2", "Michael Kramer", "narrator"),
                                ),
                            series = listOf(series("s1", "Stormlight Archive", "1")),
                            chapters =
                                listOf(
                                    chapter("ch1", "Prologue", 1_000L, 0L),
                                    chapter("ch2", "C1", 1_000L, 1_000L),
                                    chapter("ch3", "C2", 1_000L, 2_000L),
                                    chapter("ch4", "C3", 1_000L, 3_000L),
                                    chapter("ch5", "C4", 1_000L, 4_000L),
                                ),
                            audioFiles = listOf(audioFile("af1", "01.m4b", 5_000L, 1024L)),
                        )
                    repo.upsert(v1)

                    val v2 =
                        bookPayloadFixture(
                            id = "b1",
                            title = "Way of Kings",
                            contributors = listOf(contributor("c1", "Brandon Sanderson", "author")),
                            series = listOf(series("s1", "Stormlight Archive", "1")),
                            chapters =
                                listOf(
                                    chapter("nch1", "Prologue", 1_000L, 0L),
                                    chapter("nch2", "C1", 1_000L, 1_000L),
                                    chapter("nch3", "C2", 1_000L, 2_000L),
                                ),
                            audioFiles = listOf(audioFile("af1", "01.m4b", 3_000L, 1024L)),
                        )
                    val result = repo.upsert(v2)

                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    val saved = result.data
                    saved.chapters.size shouldBe 3
                    saved.chapters.map { it.id } shouldBe listOf("nch1", "nch2", "nch3")
                    saved.contributors.size shouldBe 1
                    saved.contributors[0].role shouldBe "author"
                }
            }
        }

        test("contributor dedup: same normalized name across books resolves to one row") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val repo = BookRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "Way of Kings",
                            rootRelPath = "Sanderson/Way of Kings",
                            contributors = listOf(contributor("c1", "Brandon Sanderson", "author")),
                        ),
                    )
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b2",
                            title = "Words of Radiance",
                            rootRelPath = "Sanderson/Words of Radiance",
                            // Different display, same normalized name — should resolve to same row.
                            contributors = listOf(contributor("c-different", "  Brandon  Sanderson  ", "author")),
                        ),
                    )

                    transaction(db) {
                        ContributorTable.selectAll().count() shouldBe 1L
                    }
                }
            }
        }

        test("series dedup preserves display casing of first writer") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val repo = BookRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // First book writes "Stormlight Archive".
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "Way of Kings",
                            series = listOf(series("s1", "Stormlight Archive", "1")),
                        ),
                    )
                    // Second book writes "  STORMLIGHT  archive " (whitespace + case variance).
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b2",
                            title = "Words of Radiance",
                            rootRelPath = "books/b2",
                            series = listOf(series("s2", "  STORMLIGHT  archive ", "2")),
                        ),
                    )

                    // Both books should resolve to the SAME series row,
                    // with the first writer's casing preserved.
                    suspendTransaction(db = db) {
                        val readback1 = repo.readPayloadForTest("b1")!!.series.single()
                        val readback2 = repo.readPayloadForTest("b2")!!.series.single()
                        readback1.id shouldBe readback2.id
                        readback1.name shouldBe "Stormlight Archive"
                        readback2.name shouldBe "Stormlight Archive"
                    }
                }
            }
        }

        test("FTS row is upserted in book_search and mapped via book_search_map") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val repo = BookRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "Way of Kings",
                            contributors = listOf(contributor("c1", "Brandon Sanderson", "author")),
                            series = listOf(series("s1", "Stormlight Archive", "1")),
                        ),
                    )

                    transaction(db) {
                        val mappedRowid =
                            BookSearchMapTable
                                .selectAll()
                                .where { BookSearchMapTable.bookId eq "b1" }
                                .first()[BookSearchMapTable.rowid]

                        val hits = mutableListOf<Int>()
                        exec(
                            "SELECT rowid FROM book_search WHERE book_search MATCH 'Kings' ORDER BY rank",
                        ) { rs ->
                            while (rs.next()) hits += rs.getInt(1)
                        }
                        hits shouldBe listOf(mappedRowid)

                        // Also search by contributor name — confirms FTS contributor_names column populated.
                        val byAuthor = mutableListOf<Int>()
                        exec(
                            "SELECT rowid FROM book_search WHERE book_search MATCH 'Sanderson' ORDER BY rank",
                        ) { rs ->
                            while (rs.next()) byAuthor += rs.getInt(1)
                        }
                        byAuthor shouldBe listOf(mappedRowid)
                    }
                }
            }
        }

        test("update re-uses existing rowid; book_search has exactly one row per book") {
            withInMemoryDatabase {
                val db = this
                seedLibrary(db)
                val repo = BookRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(bookPayloadFixture(id = "b1", title = "Old Title"))
                    val firstRowid =
                        transaction(db) {
                            BookSearchMapTable
                                .selectAll()
                                .where { BookSearchMapTable.bookId eq "b1" }
                                .first()[BookSearchMapTable.rowid]
                        }

                    repo.upsert(bookPayloadFixture(id = "b1", title = "New Title"))
                    transaction(db) {
                        // Mapping unchanged.
                        BookSearchMapTable
                            .selectAll()
                            .where { BookSearchMapTable.bookId eq "b1" }
                            .first()[BookSearchMapTable.rowid] shouldBe firstRowid

                        // Old title no longer matches; new title does.
                        val oldHits = mutableListOf<Int>()
                        exec("SELECT rowid FROM book_search WHERE book_search MATCH 'Old' ORDER BY rank") { rs ->
                            while (rs.next()) oldHits += rs.getInt(1)
                        }
                        oldHits shouldBe emptyList()

                        val newHits = mutableListOf<Int>()
                        exec("SELECT rowid FROM book_search WHERE book_search MATCH 'New' ORDER BY rank") { rs ->
                            while (rs.next()) newHits += rs.getInt(1)
                        }
                        newHits shouldBe listOf(firstRowid)
                    }
                }
            }
        }
    })

// --- Fixtures ---------------------------------------------------------------

private fun seedLibrary(db: Database) {
    transaction(db) {
        LibraryTable.insert {
            it[id] = "lib1"
            it[name] = "Default"
            it[rootPath] = "/lib"
        }
    }
}

private fun bookPayloadFixture(
    id: String,
    title: String,
    rootRelPath: String = "books/$id",
    contributors: List<BookContributorPayload> = emptyList(),
    series: List<BookSeriesPayload> = emptyList(),
    chapters: List<BookChapterPayload> = emptyList(),
    audioFiles: List<BookAudioFilePayload> = emptyList(),
    cover: CoverPayload? = null,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        title = title,
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
        totalDuration = audioFiles.sumOf { it.duration },
        cover = cover,
        rootRelPath = rootRelPath,
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = contributors,
        series = series,
        audioFiles = audioFiles,
        chapters = chapters,
        // The substrate authors the persisted revision/timestamps; the wire payload
        // values here are placeholders for the test, ignored by writePayload.
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

private fun contributor(
    id: String,
    name: String,
    role: String,
    sortName: String? = null,
    creditedAs: String? = null,
): BookContributorPayload =
    BookContributorPayload(
        id = id,
        name = name,
        sortName = sortName,
        role = role,
        creditedAs = creditedAs,
    )

private fun series(
    id: String,
    name: String,
    sequence: String?,
): BookSeriesPayload =
    BookSeriesPayload(
        id = id,
        name = name,
        sequence = sequence,
    )

private fun chapter(
    id: String,
    title: String,
    duration: Long,
    startTime: Long,
): BookChapterPayload =
    BookChapterPayload(
        id = id,
        title = title,
        duration = duration,
        startTime = startTime,
    )

private fun audioFile(
    id: String,
    filename: String,
    duration: Long,
    size: Long,
    format: String = "m4b",
    codec: String = "aac",
    index: Int = 0,
): BookAudioFilePayload =
    BookAudioFilePayload(
        id = id,
        index = index,
        filename = filename,
        format = format,
        codec = codec,
        duration = duration,
        size = size,
    )
