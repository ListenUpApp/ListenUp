package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.ActiveSessionRepository
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.LeaderboardRepository
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.StatsRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.series.ObserveNextInSeriesUseCase
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.flowOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
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
 *  - [ServerReachability] — owned by `clientSyncModule`.
 *  - [UserProfileRepository] — owned by `socialModule`.
 *  - [LoadedBookRepository] — owned by `playerModule`.
 *  - [PlaybackPositionRepository] — owned by `listeningModule`.
 *  - [InboxRepository] — owned by `collectionModule`.
 *  - [ObserveNextInSeriesUseCase] — owned by `seriesModule`.
 *  - [BookAvailability] — owned by `clientSyncModule`.
 *  - [BookRatingRepository] — owned by `shelfModule`.
 *  - [NetworkMonitor] — owned by each platform's storage module.
 *  - [Function1] — `HomeSlotPresenter`'s next-in-series lookup, a lambda built in the same definition from
 *    [ObserveNextInSeriesUseCase], so it is never a binding of its own.
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
                        ServerReachability::class,
                        UserProfileRepository::class,
                        LoadedBookRepository::class,
                        PlaybackPositionRepository::class,
                        InboxRepository::class,
                        ObserveNextInSeriesUseCase::class,
                        BookAvailability::class,
                        BookRatingRepository::class,
                        NetworkMonitor::class,
                        Function1::class,
                    ),
            )
        }

        test("ActivityFeedViewModel resolves with the signed-in session it marks rows by") {
            // Its constructor is internal, which verify() cannot see: resolve it for real.
            val app =
                koinApplication {
                    modules(
                        discoverPresentationModule,
                        module {
                            single<ActivityRepository> {
                                mock { every { observeRecent(any()) } returns flowOf(emptyList()) }
                            }
                            single<SyncRepository> { mock(MockMode.autoUnit) }
                            single<AuthSession> { FakeAuthSession() }
                        },
                    )
                }
            app.koin.get<ActivityFeedViewModel>() shouldNotBe null
            app.close()
        }

        test("DiscoverViewModel resolves with the user it shows in the invitation") {
            val app =
                koinApplication {
                    modules(
                        discoverPresentationModule,
                        module {
                            single<BookRepository> { mock(MockMode.autoUnit) }
                            single<ActiveSessionRepository> { mock(MockMode.autoUnit) }
                            single<AuthSession> { FakeAuthSession() }
                            single<ShelfRepository> { mock(MockMode.autoUnit) }
                            single<ServerReachability> { mock(MockMode.autoUnit) }
                            single<UserProfileRepository> { mock(MockMode.autoUnit) }
                            single<UserRepository> {
                                mock { every { observeCurrentUser() } returns flowOf(null) }
                            }
                            single { ErrorBus() }
                        },
                    )
                }
            app.koin.get<DiscoverViewModel>() shouldNotBe null
            app.close()
        }
    })
