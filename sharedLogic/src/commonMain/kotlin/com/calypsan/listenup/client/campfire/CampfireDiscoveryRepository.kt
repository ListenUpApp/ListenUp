package com.calypsan.listenup.client.campfire

import com.calypsan.listenup.api.dto.campfire.OpenCampfireSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.CampfireRefreshSignal
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform

private val logger = KotlinLogging.logger {}

/**
 * In-memory discovery mirror for open campfires (co-listening design spec §7) — the book-detail
 * live badge and the Discover "Live now" row. Deliberately NOT Room-backed: see
 * [CampfireTransport]'s KDoc — campfires are ephemeral, single-process, server-restart-forgets
 * rooms with no persistence boundary, so there is nothing here for a Room mirror to decouple
 * callers from. The in-memory cache plays the same "offline-first read source" role Room plays
 * for [com.calypsan.listenup.client.data.repository.ActiveSessionRepositoryImpl] and
 * [com.calypsan.listenup.client.data.repository.BookReadersRepositoryImpl] — those repositories'
 * shape (fetch on subscribe, re-fetch on presence ping, keep the cache on failure) is mirrored here
 * verbatim, with the in-memory cache standing in for their Room mirror.
 *
 * Each subscriber gets its own cold "fetch on subscribe, re-fetch on every [CampfireRefreshSignal]
 * ping" pipeline, so a screen (Discover, Book Detail) refreshes on entry with no separate
 * lifecycle wiring. On RPC failure the last-known list is kept (Never-Stranded — possibly stale,
 * never blank); the next ping or subscribe recovers.
 *
 * `internal`: consumed only from within `:sharedLogic`. The book-detail and Discover ViewModels
 * are public (so `sharedUI` can resolve them via `koinViewModel()`), so they never take this type
 * directly as a constructor parameter — Kotlin would refuse to let a public constructor expose an
 * internal parameter type. They instead take a plain `Flow`/lambda seam over the public
 * [OpenCampfireSummary] DTO, with the Koin binding (`CampfireClientModule`) closing over this
 * repository inside the factory lambda. [com.calypsan.listenup.client.presentation.campfire.CampfireViewModel]
 * is the one consumer that DOES reference this package's internal types directly, via an
 * `internal constructor` (see its KDoc).
 *
 * @property transport Supplies [com.calypsan.listenup.api.CampfireService.listOpenSessions].
 * @property refreshSignal Pings whenever discoverable campfires may have changed.
 */
internal class CampfireDiscoveryRepository(
    private val transport: CampfireTransport,
    private val refreshSignal: CampfireRefreshSignal,
) {
    private val cache = MutableStateFlow<List<OpenCampfireSummary>>(emptyList())

    /**
     * Every open (or invite-visible) campfire the caller may currently discover — the Discover
     * "Live now" row. Fetches on subscribe and re-fetches on every [CampfireRefreshSignal] ping.
     */
    fun observeOpenSessions(): Flow<List<OpenCampfireSummary>> = merge(refreshOnPing(), cache)

    /** Open campfires for one book — drives the book-detail live badge. */
    fun campfiresForBook(bookId: String): Flow<List<OpenCampfireSummary>> =
        observeOpenSessions().map { sessions -> sessions.filter { it.bookId == bookId } }

    private fun refreshOnPing(): Flow<List<OpenCampfireSummary>> =
        refreshSignal.signal
            .onStart { emit(Unit) } // fetch on subscribe (screen entry), before the first ping
            .transform { refresh() } // never emits — cache carries the data

    /** Re-fetch `listOpenSessions()` and replace the cache; leave it intact (Never-Stranded) on failure. */
    private suspend fun refresh() {
        try {
            when (val result = transport.listOpenSessions()) {
                is AppResult.Success -> {
                    cache.value = result.data
                }

                is AppResult.Failure -> {
                    logger.warn { "listOpenSessions refresh failed (${result.error.code}); keeping cached list" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Never-Stranded: a fault during refresh must not blank the cache or kill the flow.
            logger.warn(e) { "listOpenSessions refresh failed; keeping cached list" }
        }
    }
}
