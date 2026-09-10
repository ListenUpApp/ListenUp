package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.ActiveSessionRepository
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.LeaderboardRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.StatsRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [discoverPresentationModule] — the Home and Discover surfaces, which are the
 * first screens a signed-in reader sees, so a missing binding here is a crash on launch.
 *
 * The whitelist enumerates dependencies [discoverPresentationModule] pulls in but other modules own:
 *
 *  - [HomeRepository] — owned by `homeModule`.
 *  - [UserRepository] — owned by `socialModule`.
 *  - [ShelfRepository] — owned by `shelfModule`.
 *  - [SyncRepository] — owned by `clientSyncModule`.
 *  - [StatsRepository] — owned by `listeningModule`.
 *  - [BookRepository] — owned by `bookModule`.
 *  - [ActiveSessionRepository] — owned by `listeningModule`.
 *  - [AuthSession] — owned by `authModule`.
 *  - [ErrorBus] — owned by `appCoreModule`.
 *  - [LeaderboardRepository] — owned by `socialModule`.
 *  - [ActivityRepository] — owned by `socialModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class DiscoverPresentationModuleVerifyTest :
    FunSpec({

        test("discoverPresentationModule wires up against its declared external dependencies") {
            discoverPresentationModule.verify(
                extraTypes =
                    listOf(
                        HomeRepository::class,
                        UserRepository::class,
                        ShelfRepository::class,
                        SyncRepository::class,
                        StatsRepository::class,
                        BookRepository::class,
                        ActiveSessionRepository::class,
                        AuthSession::class,
                        ErrorBus::class,
                        LeaderboardRepository::class,
                        ActivityRepository::class,
                    ),
            )
        }
    })
