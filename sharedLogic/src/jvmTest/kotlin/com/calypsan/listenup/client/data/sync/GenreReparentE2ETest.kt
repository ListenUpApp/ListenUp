package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e for [com.calypsan.listenup.api.GenreService.moveGenre].
 *
 * Verifies that moving a subtree on the server emits a `genre.Updated` for every
 * subtree node, and the client engine applies each event into Room so paths
 * and depths reflect the new parent.
 *
 * Also covers the `/fic` vs `/fiction`
 * LIKE-collision: moving a genre whose path is a prefix of another genre's path
 * must not touch the other subtree.
 */
class GenreReparentE2ETest :
    FunSpec({

        test("moveGenre on a 3-node subtree updates client Room paths + depths") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                val oldRoot = (genreRepository.createGenre("Old") as AppResult.Success).data
                val newRoot = (genreRepository.createGenre("New") as AppResult.Success).data
                val fantasy =
                    (genreRepository.createGenre("Fantasy", parentId = oldRoot) as AppResult.Success).data
                val epic =
                    (genreRepository.createGenre("Epic Fantasy", parentId = fantasy) as AppResult.Success).data

                // Wait for the seed catch-up — all four nodes present.
                waitUntil { clientDatabase.genreDao().getById(epic.value) != null }
                clientDatabase.genreDao().getById(fantasy.value)?.path shouldBe "/old/fantasy"
                clientDatabase.genreDao().getById(epic.value)?.path shouldBe "/old/fantasy/epic-fantasy"

                // Move fantasy under new — emits two `genre.Updated` (fantasy + epic).
                val move = genreRepository.moveGenre(fantasy, newRoot)
                require(move is AppResult.Success)

                waitUntil { clientDatabase.genreDao().getById(epic.value)?.path == "/new/fantasy/epic-fantasy" }

                val fantasyRow = clientDatabase.genreDao().getById(fantasy.value)
                fantasyRow?.path shouldBe "/new/fantasy"
                fantasyRow?.depth shouldBe 1
                fantasyRow?.parentId shouldBe newRoot.value

                val epicRow = clientDatabase.genreDao().getById(epic.value)
                epicRow?.path shouldBe "/new/fantasy/epic-fantasy"
                epicRow?.depth shouldBe 2
            }
        }

        test("moveGenre on /fic does not touch /fiction subtree") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                val newRoot = (genreRepository.createGenre("New Root") as AppResult.Success).data
                val fic = (genreRepository.createGenre("Fic") as AppResult.Success).data
                val fiction = (genreRepository.createGenre("Fiction") as AppResult.Success).data
                val fictionFantasy =
                    (genreRepository.createGenre("Fantasy", parentId = fiction) as AppResult.Success).data

                waitUntil { clientDatabase.genreDao().getById(fictionFantasy.value) != null }
                clientDatabase.genreDao().getById(fic.value)?.path shouldBe "/fic"
                clientDatabase.genreDao().getById(fictionFantasy.value)?.path shouldBe "/fiction/fantasy"

                val move = genreRepository.moveGenre(fic, newRoot)
                require(move is AppResult.Success)

                waitUntil { clientDatabase.genreDao().getById(fic.value)?.path == "/new-root/fic" }

                // /fiction subtree must be untouched.
                clientDatabase.genreDao().getById(fiction.value)?.path shouldBe "/fiction"
                clientDatabase.genreDao().getById(fictionFantasy.value)?.path shouldBe "/fiction/fantasy"
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
