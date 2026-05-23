package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.server.api.PlaybackServiceImpl
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.UserStatsBackfillService
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.services.UserStatsUpdater
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for the playback slice. Wires:
 *
 *  - [AudioFileLocator] — resolves `(bookId, fileId)` to an on-disk path for the audio route.
 *  - [AudioUrlSigner] — mints and verifies short-lived signed audio URLs. Derives its signing
 *    key from the JWT secret so operators manage no extra secret.
 *  - [PlaybackPositionRepository] — per-user `(userId, bookId)` resume positions; `createdAtStart = true`
 *    so its `init` block registers `"playback_positions"` with [com.calypsan.listenup.server.sync.SyncRegistry]
 *    at bootstrap.
 *  - [UserStatsUpdater] — incremental updater wired into [ListeningEventRepository] and
 *    [PlaybackPositionRepository]; drives the materialized `user_stats` row.
 *  - [UserStatsRepository] — materialized per-user stats; `createdAtStart = true`; receives
 *    [UserStatsUpdater] for lazy window-decay on catch-up.
 *  - [ListeningEventRepository] — per-user listening spans; `createdAtStart = true`; fires
 *    [UserStatsUpdater.onListeningEvent] atomically on every upsert.
 *  - [UserStatsBackfillService] — admin-only service that rebuilds the materialized `user_stats`
 *    row from scratch; surfaced via `POST /api/v1/admin/stats/backfill`.
 *  - [PlaybackService] / [PlaybackServiceImpl] — the RPC+REST implementation. Bound at module level
 *    with an unscoped [PrincipalProvider] placeholder; route handlers call [PlaybackServiceImpl.copyWith]
 *    to scope each request to the authenticated caller.
 *
 * Installed only when a library path is configured (inside the `if (resolvedLibraryPath != null)` guard
 * in [com.calypsan.listenup.server.Application]), consistent with [booksModule] — no library means no
 * audio files to locate or sign URLs for.
 *
 * Exposed as a **function** rather than a top-level `val` so each Koin container receives a fresh
 * [Module], preventing cross-container contamination in tests.
 */
fun playbackModule(): Module =
    module {
        single { AudioFileLocator(get()) }
        single {
            AudioUrlSigner(
                signingKey = AudioUrlSigner.deriveSigningKey(get<JwtConfiguration>().secret),
            )
        }
        single(createdAtStart = true) { PlaybackPositionRepository(get(), get(), get()) }
        // Wire the stats repositories without the circular decay reference at construction time.
        // UserStatsUpdater → UserStatsRepository: direct, constructed first.
        // UserStatsRepository → UserStatsUpdater: would create a cycle — the optional `userStatsUpdater`
        // param stays null in the Koin binding; the lazy-decay path is exercised in tests that
        // construct both manually. Production stats are kept current by the on-event updater.
        single(createdAtStart = true) { UserStatsRepository(db = get(), bus = get(), registry = get()) }
        single { UserStatsUpdater(db = get(), userStatsRepo = get()) }
        single(createdAtStart = true) {
            ListeningEventRepository(db = get(), bus = get(), registry = get(), userStatsUpdater = get())
        }
        single { UserStatsBackfillService(db = get(), userStatsRepo = get()) }
        single<PlaybackService> {
            PlaybackServiceImpl(
                bookRepository = get<BookRepository>(),
                audioFileLocator = get(),
                audioUrlSigner = get(),
                playbackPositionRepository = get(),
                listeningEventRepository = get(),
                userStatsRepository = get(),
                principal =
                    PrincipalProvider {
                        error(
                            "Unscoped PlaybackService — call copyWith(PrincipalProvider) at the route",
                        )
                    },
            )
        }
    }
