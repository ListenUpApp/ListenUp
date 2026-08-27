package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e test for the chapter-set surface: a client-side call to
 * [com.calypsan.listenup.client.domain.repository.BookEditRepository.setBookChapters]
 * crosses the live kotlinx.rpc transport into the in-process `:server`'s
 * `BookService`, which replaces the chapter set and marks provenance as USER,
 * then emits an SSE `Updated<BookSyncPayload>` event. The client sync engine
 * applies the event via [com.calypsan.listenup.client.data.sync.domains.BookMirrorApply]
 * into the client Room DB, and [com.calypsan.listenup.client.domain.repository.BookRepository.observeChapters]
 * reflects the new chapter list.
 *
 * Server-side `setBookChapters` invariants (validation, provenance, rescan-protection)
 * are covered by `:server`'s `BookServiceImplSetChaptersTest`. This file proves
 * the cross-domain wiring: one client call → SSE `books` Updated → Room write,
 * observed via the [com.calypsan.listenup.client.data.local.db.ChapterDao] query.
 */
class BookChaptersE2ETest :
    FunSpec({

        test(
            "setBookChapters delivers Updated<BookSyncPayload> via SSE into client Room chapters",
        ) {
            withClientSyncEngineAgainstServer {
                // Start the engine first so the seed arrives through the live SSE tail —
                // same pattern as BookContributorsE2ETest.
                engine.start(currentUserId = "u1")
                serverBookRepository.upsert(chapterBookFixture(id = "chapters-b1", title = "Chapter Test Book"))
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.bookDao().getById(BookId("chapters-b1")) == null) {
                        // Poll until the SSE tail has applied the seed row.
                    }
                }
                // Pre-condition: no chapters for the book yet (seeded with empty chapters).
                clientDatabase.chapterDao().getChaptersForBook(BookId("chapters-b1")) shouldHaveSize 0

                // Issue the chapter-set over the real kotlinx.rpc transport. Two chapters
                // covering the full 1_200_000ms total duration.
                val result =
                    bookEditRepository.setBookChapters(
                        BookId("chapters-b1"),
                        listOf(
                            ChapterInput(
                                id = "c1",
                                title = "Prologue",
                                startTime = 0L,
                                duration = 600_000L,
                            ),
                            ChapterInput(
                                id = "c2",
                                title = "Act One",
                                startTime = 600_000L,
                                duration = 600_000L,
                            ),
                        ),
                    )
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                // Offline-first: the edit wrote chapters into Room optimistically and enqueued a
                // durable `books` op. Await the server round-trip — the engine drains the op over RPC,
                // proving the write actually reached the server. A yielding delay is load-bearing: the
                // drain runs on the same Dispatchers.Default pool the test polls on, so a tight
                // busy-spin would starve it (mirrors SetBookCollectionsReconcileE2ETest).
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (serverBookRepository.findById(BookId("chapters-b1"))?.chapters?.size != 2) {
                        delay(50)
                    }
                }

                // The optimistic Room write mirrors what the SSE echo reconciles — chapters are present.
                val chapters = clientDatabase.chapterDao().getChaptersForBook(BookId("chapters-b1"))
                chapters shouldHaveSize 2
                chapters[0].title shouldBe "Prologue"
                chapters[1].title shouldBe "Act One"

                // Dual assertion against the server side: the drained op set the same chapters.
                val serverBook = serverBookRepository.findById(BookId("chapters-b1"))
                checkNotNull(serverBook) { "server-side book row missing after setBookChapters" }
                serverBook.chapters shouldHaveSize 2
                serverBook.chapters[0].title shouldBe "Prologue"
                serverBook.chapters[1].title shouldBe "Act One"
            }
        }

        test("a book's tier vocabulary round-trips client → server → back through the sync echo") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")
                serverBookRepository.upsert(chapterBookFixture(id = "tiers-b1", title = "Tier Test Book"))
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.bookDao().getById(BookId("tiers-b1")) == null) {
                        delay(50)
                    }
                }

                bookEditRepository
                    .setBookTierLabels(BookId("tiers-b1"), bookTierLabel = "Volume", partTierLabel = "Sequence")
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                // The op drains over the real RPC transport — proof the edit reached the server,
                // not just the optimistic Room write it also made.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (serverBookRepository.findById(BookId("tiers-b1"))?.bookTierLabel != "Volume") {
                        delay(50)
                    }
                }
                serverBookRepository.findById(BookId("tiers-b1"))?.partTierLabel shouldBe "Sequence"

                // Now the READ path, isolated: change the pair server-side and watch the firehose
                // echo carry it into Room. The optimistic write cannot account for this one, so it
                // is the only assertion here that exercises the payload → entity mapping.
                serverBookRepository.setTierLabels(BookId("tiers-b1"), "Era", null)
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.bookDao().getById(BookId("tiers-b1"))?.bookTierLabel != "Era") {
                        delay(50)
                    }
                }
                val mirrored = clientDatabase.bookDao().getById(BookId("tiers-b1"))
                checkNotNull(mirrored) { "client book row missing after the tier-label echo" }
                mirrored.bookTierLabel shouldBe "Era"
                // Null crossed the wire as a value, not as "leave the old one" — a clear that
                // silently kept "Sequence" would be the exact bug the targeted update exists to avoid.
                mirrored.partTierLabel shouldBe null
            }
        }

        test("per-chapter section headers survive the whole round trip into client Room") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")
                serverBookRepository.upsert(chapterBookFixture(id = "sections-b1", title = "Section Test Book"))
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.bookDao().getById(BookId("sections-b1")) == null) {
                        delay(50)
                    }
                }

                bookEditRepository
                    .setBookChapters(
                        BookId("sections-b1"),
                        listOf(
                            ChapterInput(
                                id = "c1",
                                title = "Prologue",
                                startTime = 0L,
                                duration = 600_000L,
                                bookTitle = "Book One",
                                partTitle = "The Way of Kings",
                            ),
                            ChapterInput(id = "c2", title = "Act One", startTime = 600_000L, duration = 600_000L),
                        ),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (serverBookRepository.findById(BookId("sections-b1"))?.chapters?.size != 2) {
                        delay(50)
                    }
                }

                val serverChapters = serverBookRepository.findById(BookId("sections-b1"))?.chapters
                checkNotNull(serverChapters) { "server-side chapters missing after setBookChapters" }
                serverChapters[0].bookTitle shouldBe "Book One"
                serverChapters[0].partTitle shouldBe "The Way of Kings"
                serverChapters[1].bookTitle shouldBe null

                val clientChapters = clientDatabase.chapterDao().getChaptersForBook(BookId("sections-b1"))
                clientChapters.single { it.id.value == "c1" }.bookTitle shouldBe "Book One"
                clientChapters.single { it.id.value == "c1" }.partTitle shouldBe "The Way of Kings"
                clientChapters.single { it.id.value == "c2" }.partTitle shouldBe null
            }
        }
    })

private fun chapterBookFixture(
    id: String,
    title: String,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 1_200_000L,
        cover = null,
        rootRelPath = "books/$id",
        inode = null,
        scannedAt = 1L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles = emptyList(),
        chapters = emptyList(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
