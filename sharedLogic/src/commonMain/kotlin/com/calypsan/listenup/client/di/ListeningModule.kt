package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.StatsApi
import com.calypsan.listenup.client.data.remote.StatsApiContract
import com.calypsan.listenup.client.data.repository.ListeningEventRepositoryImpl
import com.calypsan.listenup.client.data.repository.PlaybackPositionRepositoryImpl
import com.calypsan.listenup.client.data.repository.StatsRepositoryImpl
import com.calypsan.listenup.client.domain.repository.ListeningEventRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.StatsRepository
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
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
 *  - [String] (named `"deviceId"`) — platform playback modules
 *  - [com.calypsan.listenup.client.data.sync.PendingOperationQueue] — `clientSyncRenovationModule`
 */
val listeningModule: Module =
    module {
        // StatsApi for listening statistics
        single {
            StatsApi(clientFactory = get())
        } bind StatsApiContract::class

        // StatsRepository for computing listening stats locally from events
        single<StatsRepository> {
            StatsRepositoryImpl(
                listeningEventDao = get(),
                genreDao = get(),
                authSession = get(),
            )
        }

        // ListeningEventRepository — transactional write (upsert + pending-op) + DAO read surface.
        // TODO(P2-session): Replace userId placeholder with the authenticated user ID from the
        //  active session once the P2 session-context DI binding lands. For now we use the
        //  deviceId as a stable surrogate so that existing events can be distinguished by device.
        single<ListeningEventRepository> {
            ListeningEventRepositoryImpl(
                listeningEventDao = get(),
                transactionRunner = get(),
                userId = get(qualifier = named("deviceId")),
                tz =
                    kotlinx.datetime.TimeZone
                        .currentSystemDefault()
                        .id,
                deviceLabel = null,
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
    }
