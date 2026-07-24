package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e for the genres CRUD round trip. Covers:
 *
 *  1. **Server-side substrate write → SSE → client Room.** A direct
 *     `serverGenreRepository.upsert(GenreSyncPayload)` publishes a
 *     `genre.Created` SSE event. The client engine catches up and
 *     `genresDomain` applies the payload into Room.
 *
 *  2. **Client-side RPC mutation → server → SSE → client Room.** A call to
 *     [com.calypsan.listenup.client.domain.repository.GenreRepository.createGenre]
 *     crosses the live kotlinx.rpc transport into the in-process server's
 *     `GenreService.createGenre`. The server-side substrate write triggers an
 *     SSE echo that the client engine pulls back into Room.
 *
 *  3. **updateGenre rename propagates.** RPC patch → server → SSE → Room sees
 *     the new name.
 *
 *  4. **deleteGenre tombstones the row.** RPC delete → server soft-delete → SSE
 *     → Room observes `deletedAt != null` and reads via
 *     [com.calypsan.listenup.client.domain.repository.GenreRepository.observeAll]
 *     no longer include the row.
 */
class GenreCrudE2ETest :
    FunSpec({

        test("server upsert of a Genre arrives in client Room via SSE") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                val id = UUID.randomUUID().toString()
                serverGenreRepository.upsert(
                    GenreSyncPayload(
                        id = id,
                        name = "Fantasy",
                        slug = "fantasy",
                        path = "/fantasy",
                        parentId = null,
                        depth = 0,
                        sortOrder = 0,
                    ),
                )

                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.genreDao().getById(id) == null) {
                        kotlinx.coroutines.delay(50)
                    }
                }
                val row = clientDatabase.genreDao().getById(id)
                row?.name shouldBe "Fantasy"
                row?.path shouldBe "/fantasy"
            }
        }

        test("client createGenre RPC round-trips into client Room") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                val result = genreRepository.createGenre(name = "Science Fiction")
                result.shouldBeInstanceOf<AppResult.Success<GenreId>>()
                val newId = result.data

                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.genreDao().getById(newId.value) == null) {
                        kotlinx.coroutines.delay(50)
                    }
                }
                val row = clientDatabase.genreDao().getById(newId.value)
                row?.name shouldBe "Science Fiction"
                row?.slug shouldBe "science-fiction"
                row?.path shouldBe "/science-fiction"
                row?.depth shouldBe 0
            }
        }

        test("client updateGenre rename propagates to Room") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                val createResult = genreRepository.createGenre(name = "Sci-Fi")
                require(createResult is AppResult.Success)
                val id = createResult.data

                waitUntil { clientDatabase.genreDao().getById(id.value) != null }

                val update = genreRepository.updateGenre(id, GenreUpdate(name = "Science Fiction"))
                require(update is AppResult.Success)

                waitUntil { clientDatabase.genreDao().getById(id.value)?.name == "Science Fiction" }
            }
        }

        test("client deleteGenre tombstones the row") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                val createResult = genreRepository.createGenre(name = "Doomed Genre")
                require(createResult is AppResult.Success)
                val id = createResult.data

                waitUntil { clientDatabase.genreDao().getById(id.value) != null }

                val delete = genreRepository.deleteGenre(id)
                require(delete is AppResult.Success)

                // `getById` filters live rows — after the tombstone propagates, it returns null.
                waitUntil { clientDatabase.genreDao().getById(id.value) == null }
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
