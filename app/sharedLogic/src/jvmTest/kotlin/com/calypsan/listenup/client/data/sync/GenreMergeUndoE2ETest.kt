package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

private const val TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e for genre merge undo: the merged-away genre, tombstoned on the client by the merge,
 * must come back live in client Room once the server undoes it.
 */
class GenreMergeUndoE2ETest :
    FunSpec({

        test("an undone genre merge brings the genre back in client Room") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")
                val source = (genreRepository.createGenre("Space Opera") as AppResult.Success).data
                val target = (genreRepository.createGenre("Science Fiction") as AppResult.Success).data
                waitUntil { clientDatabase.genreDao().getById(source.value) != null }

                genreRepository.mergeGenres(source, target).shouldBeInstanceOf<AppResult.Success<Unit>>()
                waitUntil { clientDatabase.genreDao().getById(source.value) == null }

                val receipt =
                    serverGenreService
                        .listMergeReceipts(target)
                        .shouldBeInstanceOf<AppResult.Success<List<MergeReceipt>>>()
                        .data
                        .single()
                        .id
                serverGenreService.undoGenreMerge(receipt).shouldBeInstanceOf<AppResult.Success<*>>()

                waitUntil { clientDatabase.genreDao().getById(source.value) != null }
            }
        }
    })

private suspend fun waitUntil(predicate: suspend () -> Boolean) {
    withTimeout(TIMEOUT_SECONDS.seconds) {
        while (!predicate()) delay(50)
    }
}
