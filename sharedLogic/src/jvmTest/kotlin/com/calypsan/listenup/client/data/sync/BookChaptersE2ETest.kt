package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.chapter.groupChapters
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
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
                                partTitle = "Part One",
                                bookTitle = "Book I",
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

                // Poll the client Room DB until the SSE-driven Book Updated event has
                // been applied by BookMirrorApply — the chapter rows exist only
                // once the handler has processed the event.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.chapterDao().getChaptersForBook(BookId("chapters-b1")).isEmpty()) {
                        // SSE delivery latency is non-deterministic; poll the real query.
                    }
                }

                val chapterEntities = clientDatabase.chapterDao().getChaptersForBook(BookId("chapters-b1"))
                chapterEntities shouldHaveSize 2
                chapterEntities[0].title shouldBe "Prologue"
                chapterEntities[1].title shouldBe "Act One"

                // Header fields must survive the full server→sync→Room round trip.
                chapterEntities[0].partTitle shouldBe "Part One"
                chapterEntities[0].bookTitle shouldBe "Book I"
                chapterEntities[1].partTitle shouldBe null
                chapterEntities[1].bookTitle shouldBe null

                // Verify the chapter set groups correctly via the domain groupChapters function.
                val domainChapters =
                    chapterEntities.map { e ->
                        Chapter(
                            id = e.id.value,
                            title = e.title,
                            duration = e.duration,
                            startTime = e.startTime,
                            partTitle = e.partTitle,
                            bookTitle = e.bookTitle,
                        )
                    }
                val groups = domainChapters.groupChapters()
                groups shouldHaveSize 1
                groups.single().title shouldBe "Book I"
                groups.single().parts shouldHaveSize 1
                groups
                    .single()
                    .parts
                    .single()
                    .title shouldBe "Part One"
                groups
                    .single()
                    .parts
                    .single()
                    .chapters shouldHaveSize 2

                // Dual assertion against the server side: the book's SSE payload
                // should carry the same chapters, proving the server end of the
                // round trip is the one driving Room.
                val serverBook = serverBookRepository.findById(BookId("chapters-b1"))
                checkNotNull(serverBook) { "server-side book row missing after setBookChapters" }
                serverBook.chapters shouldHaveSize 2
                serverBook.chapters[0].title shouldBe "Prologue"
                serverBook.chapters[1].title shouldBe "Act One"
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
