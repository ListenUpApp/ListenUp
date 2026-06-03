package com.calypsan.listenup.client.data.sync

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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30
private const val BOOK_ONE = "unmapped-e2e-b1"
private const val BOOK_TWO = "unmapped-e2e-b2"
private const val RAW_STRING = "Cyberpunk"

/**
 * Tier 3 e2e for [com.calypsan.listenup.api.GenreService.mapUnmappedToGenre].
 *
 * Seeds two books and two `pending_book_genres` rows referencing the same raw
 * string. After the client calls `mapUnmappedToGenre`, every affected book is
 * re-upserted on the server and the resulting `book.Updated` SSE events drive
 * the client's Room `book_genres` junction to reflect the new binding.
 *
 * `pending_book_genres` is a server-only table — the client doesn't mirror it
 * — so verification is done via the client Room book_genres state after the
 * cascade lands.
 */
class UnmappedGenreMappingE2ETest :
    FunSpec({

        test("mapUnmappedToGenre lands new junctions in client Room for every affected book") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                // Create the target genre via the curator path.
                val targetGenreId =
                    (genreRepository.createGenre(name = "Cyberpunk") as AppResult.Success).data

                // Seed two books on the server side directly.
                serverBookRepository.upsert(bookFixture(BOOK_ONE, "Neuromancer"))
                serverBookRepository.upsert(bookFixture(BOOK_TWO, "Snow Crash"))

                // Seed pending_book_genres rows via raw SQL — the table is `internal` and
                // not exposed via the server's public service API at the SQL-write level.
                transaction(serverDb) {
                    exec(
                        "INSERT OR IGNORE INTO pending_book_genres(book_id, raw_string, first_seen_at) " +
                            "VALUES ('$BOOK_ONE', '$RAW_STRING', 1)",
                    )
                    exec(
                        "INSERT OR IGNORE INTO pending_book_genres(book_id, raw_string, first_seen_at) " +
                            "VALUES ('$BOOK_TWO', '$RAW_STRING', 2)",
                    )
                }

                waitUntil { clientDatabase.bookDao().getById(BookId(BOOK_ONE)) != null }
                waitUntil { clientDatabase.bookDao().getById(BookId(BOOK_TWO)) != null }

                val result = genreRepository.mapUnmappedToGenre(RAW_STRING, targetGenreId)
                require(result is AppResult.Success)

                waitUntil {
                    clientDatabase.genreDao().getGenresForBook(BookId(BOOK_ONE)).any {
                        it.id == targetGenreId.value
                    } &&
                        clientDatabase.genreDao().getGenresForBook(BookId(BOOK_TWO)).any {
                            it.id == targetGenreId.value
                        }
                }
                val book1Genres = clientDatabase.genreDao().getGenresForBook(BookId(BOOK_ONE)).map { it.id }
                val book2Genres = clientDatabase.genreDao().getGenresForBook(BookId(BOOK_TWO)).map { it.id }
                book1Genres shouldContainExactlyInAnyOrder listOf(targetGenreId.value)
                book2Genres shouldContainExactlyInAnyOrder listOf(targetGenreId.value)
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
