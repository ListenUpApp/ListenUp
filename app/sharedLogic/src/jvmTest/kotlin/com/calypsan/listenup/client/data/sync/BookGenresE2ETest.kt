package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30
private const val BOOK_ID = "book-genres-e2e-1"

/**
 * Tier 3 e2e for [com.calypsan.listenup.client.domain.repository.BookEditRepository.setBookGenres].
 *
 * Client calls `setBookGenres` over RPC; the server replaces the `book_genres`
 * junction and re-upserts the book, emitting a `book.Updated` whose payload's
 * `genres` field re-derives from the live junction. Client Room sees the new
 * genres after SSE applies.
 */
class BookGenresE2ETest :
    FunSpec({

        test("setBookGenres round-trips into client Room book_genres junction") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                val fantasy = (genreRepository.createGenre("Fantasy") as AppResult.Success).data
                val scifi = (genreRepository.createGenre("Sci-Fi") as AppResult.Success).data

                waitUntil { clientDatabase.genreDao().getById(fantasy.value) != null }
                waitUntil { clientDatabase.genreDao().getById(scifi.value) != null }

                serverBookRepository.upsert(bookFixture(id = BOOK_ID, title = "Genre-tagged Book"))
                waitUntil { clientDatabase.bookDao().getById(BookId(BOOK_ID)) != null }

                val result =
                    bookEditRepository.setBookGenres(
                        BookId(BOOK_ID),
                        listOf(BookGenreInput(fantasy), BookGenreInput(scifi)),
                    )
                require(result is AppResult.Success)

                waitUntil {
                    clientDatabase.genreDao().getGenresForBook(BookId(BOOK_ID)).size == 2
                }
                clientDatabase
                    .genreDao()
                    .getGenresForBook(BookId(BOOK_ID))
                    .map { it.id }
                    .toSet()
                    .let { it shouldContainExactlyInAnyOrder setOf(fantasy.value, scifi.value) }
            }
        }
    })

private suspend fun waitUntil(predicate: suspend () -> Boolean) {
    withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
        while (!predicate()) {
            kotlinx.coroutines.delay(50)
        }
    }
}

private fun bookFixture(
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
        totalDuration = 3_600_000L,
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
