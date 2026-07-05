package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.repository.ListeningEventRepositoryImpl
import com.calypsan.listenup.client.data.repository.PlaybackPositionRepositoryImpl
import com.calypsan.listenup.client.data.repository.StatsRepositoryImpl
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ListeningEventRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.StatsRepository
import com.calypsan.listenup.client.playback.ListeningEventRecorder
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Listening aggregate Koin wiring — API, repositories for the listening/stats domain
 * (listening stats, listening events, playback positions).
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.data.local.db.ListeningEventDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.GenreDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.PlaybackPositionDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.TransactionRunner] — `persistenceModule`
 *  - [com.calypsan.listenup.client.domain.repository.AuthSession] — `clientAuthModule`
 *  - [com.calypsan.listenup.client.data.sync.PendingOperationQueue] — `clientSyncModule`
 */
internal val listeningModule: Module =
    module {
        // StatsRepository for computing listening stats locally from events
        single<StatsRepository> {
            StatsRepositoryImpl(
                listeningEventDao = get(),
                genreDao = get(),
                playbackPositionDao = get(),
                authSession = get(),
            )
        }

        // ListeningEventRepository — read-only DAO surface for stats/leaderboard.
        // Listening-event writes are owned by the canonical recording path
        // (ListeningEventRecorder, bound below).
        single<ListeningEventRepository> {
            ListeningEventRepositoryImpl(
                listeningEventDao = get(),
            )
        }

        // PlaybackPositionRepository for position tracking (SOLID: interface in domain, impl in data)
        single<PlaybackPositionRepository> {
            PlaybackPositionRepositoryImpl(
                dao = get(),
                transactionRunner = get(),
                pendingQueue = get(),
                authSession = get(),
            )
        }

        // Listening event recorder — span state machine for listening history.
        // Single source for the binding (previously duplicated across all four platform
        // playback modules); platform-independent because every dependency resolves via get().
        single {
            ListeningEventRecorder(
                listeningEventDao = get(),
                tentativeSpanDao = get(),
                enqueue = { entityId, payload, ownerUserId ->
                    get<PendingOperationQueue>()
                        .enqueue(OutboxChannels.ListeningEvents, entityId, OpKind.Upsert, payload, ownerUserId)
                },
                currentUserId = { get<AuthSession>().getUserId() },
                deviceInfo = get(),
            )
        }
    }
