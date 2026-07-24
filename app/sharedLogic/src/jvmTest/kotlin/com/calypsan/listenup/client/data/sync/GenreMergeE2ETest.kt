package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e for [com.calypsan.listenup.api.GenreService.mergeGenres].
 *
 * Server-side merge cascades — junction relink, alias repoint, source tombstone —
 * are covered by `:server`'s `GenreServiceImplMergeTest`. This test proves the
 * wire round-trip: a client RPC call lands the cascade in client Room such that
 * the source row is tombstoned (no longer observed by `getById`) and only the
 * target remains live.
 */
class GenreMergeE2ETest :
    FunSpec({

        test("mergeGenres tombstones the source on the client side") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                val source = (genreRepository.createGenre("Source Genre") as AppResult.Success).data
                val target = (genreRepository.createGenre("Target Genre") as AppResult.Success).data

                waitUntil { clientDatabase.genreDao().getById(source.value) != null }
                waitUntil { clientDatabase.genreDao().getById(target.value) != null }

                val merge = genreRepository.mergeGenres(source, target)
                require(merge is AppResult.Success)

                // Source should be tombstoned; getById filters live rows.
                waitUntil { clientDatabase.genreDao().getById(source.value) == null }

                // Target stays live.
                clientDatabase.genreDao().getById(target.value)?.name shouldBe "Target Genre"
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
