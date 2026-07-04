package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.ActivityService
import com.calypsan.listenup.api.PlaybackProgressService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.server.api.ActivityServiceImpl
import com.calypsan.listenup.server.api.PlaybackProgressServiceImpl
import com.calypsan.listenup.server.api.PlaybackServiceImpl
import com.calypsan.listenup.server.api.SocialServiceImpl
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.audio.CoverUrlSigner
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserRoleLookup
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.scheduler.ActiveSessionCleanupTask
import com.calypsan.listenup.server.scheduler.StatsFreshnessSweepTask
import com.calypsan.listenup.server.services.ActiveSessionRepository
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ActivityRepository
import com.calypsan.listenup.server.services.ActivitySyncRepository
import com.calypsan.listenup.server.services.BookReadsRepository
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.StatsRecorder
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
 *  - [ActiveSessionRepository] — per-user active listening sessions; `createdAtStart = true`; injected into
 *    [PlaybackPositionRepository] to cascade a hard-delete when a book's `finished` flag flips `false→true`.
 *  - [StatsRecorder] — the single write choke-point for every stats-affecting trigger; dispatches each
 *    `StatsEvent` through a fixed ordering (durable source rows → `user_stats` →
 *    `public_profiles.refresh()` → activity emission). Injected into [PlaybackPositionRepository] and
 *    [ListeningEventRepository] in place of the direct [UserStatsUpdater]/[BookReadsRepository]/
 *    [ActivityRecorder] wiring they used before.
 *  - [PlaybackPositionRepository] — per-user `(userId, bookId)` resume positions; `createdAtStart = true`
 *    so its `init` block registers `"playback_positions"` with [com.calypsan.listenup.server.sync.SyncRegistry]
 *    at bootstrap. Receives [ActiveSessionRepository] for the completion cascade and [StatsRecorder] to
 *    route the finish-flip / start-and-re-read cascade.
 *  - [UserStatsUpdater] — the lazy window-decay self-heal [UserStatsRepository.pullSince] depends on; the
 *    event-driven write cascade it used to own now lives in [StatsRecorder].
 *  - [UserStatsRepository] — materialized per-user stats; `createdAtStart = true`; receives
 *    [UserStatsUpdater] via a **lazy provider** (`userStatsUpdaterProvider = { get<UserStatsUpdater>() }`)
 *    to break the construction-time mutual reference: [UserStatsRepository] needs [UserStatsUpdater]
 *    for lazy window-decay, and [UserStatsUpdater] needs [UserStatsRepository] to write recomputed rows.
 *    The provider is only invoked at runtime inside `pullSince`, by which time both singletons resolve.
 *  - [ListeningEventRepository] — per-user listening spans; `createdAtStart = true`; fires
 *    `statsRecorder.record(StatsEvent.ListeningSessionClosed(...))` atomically on every upsert.
 *  - [UserStatsBackfillService] — admin-only service that rebuilds the materialized `user_stats`
 *    row from scratch; surfaced via `POST /api/v1/admin/stats/backfill`, and called by [StatsRecorder]
 *    to service `StatsEvent.BulkRecompute`.
 *  - [ActiveSessionCleanupTask] — periodic sweep that hard-deletes stale `active_sessions` rows
 *    left by ungraceful disconnects. Started on the application scope in [Application.module].
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
        single {
            CoverUrlSigner(
                signingKey = CoverUrlSigner.deriveSigningKey(get<JwtConfiguration>().secret),
            )
        }
        single { UserRoleLookup(db = get<ListenUpDatabase>()) }
        single(createdAtStart = true) { ActiveSessionRepository(db = get<ListenUpDatabase>(), bus = get()) }
        single(createdAtStart = true) {
            ActivitySyncRepository(
                db = get<ListenUpDatabase>(),
                bus = get(),
                registry = get(),
                driver = get<SqlDriver>(),
            )
        }
        single { ActivityRepository(db = get<ListenUpDatabase>()) }
        single { ActivityRecorder(syncRepo = get()) }
        single { BookReadsRepository(db = get<ListenUpDatabase>(), clock = get()) }
        single {
            StatsRecorder(
                sql = get<ListenUpDatabase>(),
                userStatsRepo = get(),
                bookReadsRepository = get(),
                publicProfileMaintainer = get(),
                activityRecorder = get(),
                statsBackfill = get(),
            )
        }
        single(createdAtStart = true) {
            PlaybackPositionRepository(
                db = get<ListenUpDatabase>(),
                bus = get(),
                registry = get(),
                statsRecorder = get(),
                activeSessionRepo = get(),
            )
        }
        // UserStatsRepository references UserStatsUpdater (for lazy window decay), while
        // UserStatsUpdater references UserStatsRepository (to write recomputed rows). The lazy
        // provider `{ get<UserStatsUpdater>() }` breaks the construction-time cycle: Koin resolves
        // UserStatsRepository first, then UserStatsUpdater. The provider is only invoked at
        // runtime inside pullSince(), by which time both singletons are fully resolved.
        single(createdAtStart = true) {
            UserStatsRepository(
                db = get<ListenUpDatabase>(),
                bus = get(),
                registry = get(),
                userStatsUpdaterProvider = { get<UserStatsUpdater>() },
            )
        }
        single {
            UserStatsUpdater(
                sql = get<ListenUpDatabase>(),
                userStatsRepo = get(),
                publicProfileMaintainer = get(),
            )
        }
        single(createdAtStart = true) {
            ListeningEventRepository(
                db = get<ListenUpDatabase>(),
                bus = get(),
                registry = get(),
                statsRecorder = get(),
            )
        }
        single { UserStatsBackfillService(sql = get<ListenUpDatabase>(), userStatsRepo = get()) }
        single { ActiveSessionCleanupTask(sql = get(), bus = get()) }
        single { StatsFreshnessSweepTask(sql = get(), updater = get()) }
        single<PlaybackService> {
            PlaybackServiceImpl(
                bookRepository = get<BookRepository>(),
                audioFileLocator = get(),
                audioUrlSigner = get(),
                coverUrlSigner = get(),
                playbackPositionRepository = get(),
                listeningEventRepository = get(),
                userStatsRepository = get(),
                accessPolicy = get(),
                principal =
                    PrincipalProvider {
                        error(
                            "Unscoped PlaybackService — call copyWith(PrincipalProvider) at the route",
                        )
                    },
                sql = get<ListenUpDatabase>(),
            )
        }
        single<PlaybackProgressService> {
            PlaybackProgressServiceImpl(
                repository = get(),
                principal =
                    PrincipalProvider {
                        error(
                            "Unscoped PlaybackProgressService — call copyWith(PrincipalProvider) at the route",
                        )
                    },
            )
        }
        single {
            SocialServiceImpl(
                activeSessions = get(),
                bookAccessPolicy = get(),
                publicProfiles = get(),
                playbackPositions = get(),
                bookReads = get(),
                books = get(),
                principal = unscopedSocialPlaceholder(),
            )
        }
        single<SocialService> { get<SocialServiceImpl>() }
        single {
            ActivityServiceImpl(
                activities = get(),
                bookAccessPolicy = get(),
                publicProfiles = get(),
                principal = unscopedActivityPlaceholder(),
            )
        }
        single<ActivityService> { get<ActivityServiceImpl>() }
    }

/**
 * The unscoped-caller placeholder the [SocialServiceImpl] binding carries: a
 * [PrincipalProvider] that throws if invoked. The RPC route always `copyWith`s the
 * authenticated principal before calling, so reaching this placeholder signals a wiring
 * bug — fail loud rather than silently serving an unscoped (ACL-bypassing) view.
 */
private fun unscopedSocialPlaceholder(): PrincipalProvider =
    PrincipalProvider { error("Unscoped SocialService — call copyWith(PrincipalProvider) at the route") }

/**
 * The unscoped-caller placeholder the [ActivityServiceImpl] binding carries: a
 * [PrincipalProvider] that throws if invoked. The RPC route always `copyWith`s the
 * authenticated principal before calling, so reaching this placeholder signals a wiring
 * bug — fail loud rather than silently serving an unscoped (ACL-bypassing) feed.
 */
private fun unscopedActivityPlaceholder(): PrincipalProvider =
    PrincipalProvider { error("Unscoped ActivityService — call copyWith(PrincipalProvider) at the route") }
