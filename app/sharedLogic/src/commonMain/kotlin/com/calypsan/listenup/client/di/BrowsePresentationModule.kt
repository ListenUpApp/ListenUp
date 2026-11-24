package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.usecase.series.ObserveNextInSeriesUseCase
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetViewModel
import com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksViewModel
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailViewModel
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditViewModel
import com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import com.calypsan.listenup.client.presentation.discover.LeaderboardViewModel
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationViewModel
import com.calypsan.listenup.client.presentation.home.HomeSlotPresenter
import com.calypsan.listenup.client.presentation.home.HomeStatsViewModel
import com.calypsan.listenup.client.presentation.home.HomeViewModel
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditViewModel
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfViewModel
import com.calypsan.listenup.client.presentation.shelf.MyShelvesViewModel
import com.calypsan.listenup.client.presentation.shelf.ShelfDetailViewModel
import kotlinx.coroutines.flow.combine
import org.koin.dsl.module

/**
 * Series ViewModels.
 */
internal val seriesPresentationModule =
    module {
        factory {
            SeriesDetailViewModel(
                seriesRepository = get<SeriesRepository>(),
                imageRepository = get(),
                playbackPositionRepository = get(),
                permissionsRepository = get(),
                networkMonitor = get(),
                seriesEditRepository = get<SeriesEditRepository>(),
                errorBus = get(),
            )
        }
        factory {
            SeriesEditViewModel(
                seriesRepository = get<SeriesRepository>(),
                updateSeriesUseCase = get(),
                imageRepository = get(),
                imageStagingRepository = get(),
                seriesEditRepository = get<SeriesEditRepository>(),
                seriesDao = get(),
                errorBus = get(),
                networkMonitor = get(),
                permissionsRepository = get(),
            )
        }
    }

/**
 * Contributor ViewModels.
 */
internal val contributorPresentationModule =
    module {
        factory {
            ContributorDetailViewModel(
                contributorRepository = get<ContributorRepository>(),
                playbackPositionRepository = get(),
                seriesRepository = get<SeriesRepository>(),
                deleteContributorUseCase = get(),
                permissionsRepository = get(),
                contributorEditRepository = get(),
                errorBus = get(),
            )
        }
        factory {
            ContributorBooksViewModel(
                contributorRepository = get<ContributorRepository>(),
                playbackPositionRepository = get(),
            )
        }
        factory {
            ContributorEditViewModel(
                contributorRepository = get<ContributorRepository>(),
                updateContributorUseCase = get(),
                imageRepository = get(),
                imageStagingRepository = get(),
                pendingImageUploads = get(),
                contributorEditRepository = get<ContributorEditRepository>(),
                contributorAliasDao = get(),
                contributorDao = get(),
                errorBus = get(),
                permissionsRepository = get(),
            )
        }
    }

/**
 * Discover and social ViewModels.
 */
internal val discoverPresentationModule =
    module {
        factory {
            HomeSlotPresenter(
                loadedBooks = get(),
                positions = get(),
                inbox = get(),
                nextInSeries = get<ObserveNextInSeriesUseCase>()::invoke,
                books = get(),
                ratings = get(),
                activities = get(),
                authSession = get(),
                home = get(),
            )
        }
        factory {
            HomeViewModel(
                homeRepository = get(),
                userRepository = get(),
                shelfRepository = get(),
                syncRepository = get(),
                slotPresenter = get(),
                serverReachability = get(),
                networkMonitor = get(),
                errorBus = get(),
            )
        }
        // HomeStatsViewModel for home screen stats section (observes local stats)
        factory {
            val sync = get<SyncRepository>()
            HomeStatsViewModel(
                statsRepository = get(),
                libraryArriving = combine(sync.buildingInitialLibrary, sync.firstCatchUpRunning) { a, b -> a || b },
            )
        }
        factory {
            DiscoverViewModel(
                bookRepository = get(),
                activeSessionRepository = get(),
                authSession = get(),
                shelfRepository = get(),
                serverReachability = get(),
                userProfileRepository = get(),
                userRepository = get(),
                errorBus = get(),
            )
        }
        // LeaderboardViewModel for discover screen leaderboard
        factory { LeaderboardViewModel(repo = get(), authSession = get()) }
        // ActivityFeedViewModel for discover screen activity feed
        factory {
            ActivityFeedViewModel(
                activityRepository = get(),
                activeSessionRepository = get(),
                syncRepository = get(),
                authSession = get(),
                serverReachability = get(),
            )
        }
    }

/**
 * Shelf ViewModels.
 */
internal val shelfPresentationModule =
    module {
        factory {
            MyShelvesViewModel(
                userRepository = get(),
                shelfRepository = get(),
                syncRepository = get(),
            )
        }
        factory {
            ShelfDetailViewModel(
                loadShelfDetailUseCase = get(),
                removeBookFromShelfUseCase = get(),
                reorderShelfBooksUseCase = get(),
            )
        }
        factory {
            CreateEditShelfViewModel(
                createShelfUseCase = get(),
                updateShelfUseCase = get(),
                deleteShelfUseCase = get(),
                shelfRepository = get(),
                errorBus = get(),
            )
        }
    }

/**
 * Facet-browse ViewModels — "show every book matching one categorization" (genre, tag, or mood).
 * Reached from the genre/tag/mood chips on Book Detail; each is a pure read over the library, so
 * these belong with the browsing surfaces, not the admin console where they originally landed.
 */
internal val browsePresentationModule =
    module {
        factory {
            BrowseFacetViewModel(
                tagRepository = get(),
                moodRepository = get(),
                bookRepository = get(),
            )
        }
        factory {
            GenreDestinationViewModel(
                genreRepository = get(),
                bookRepository = get(),
            )
        }
    }
