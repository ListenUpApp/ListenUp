package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel
import com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel
import com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel
import com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel
import com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel
import com.calypsan.listenup.client.presentation.discover.LeaderboardViewModel
import com.calypsan.listenup.client.presentation.home.HomeStatsViewModel
import com.calypsan.listenup.client.presentation.invite.InviteRegistrationViewModel
import com.calypsan.listenup.client.presentation.library.LibraryActionsViewModel
import com.calypsan.listenup.client.presentation.library.LibrarySelectionManager
import com.calypsan.listenup.client.presentation.library.LibraryViewModel
import com.calypsan.listenup.client.download.DownloadFileManager
import com.calypsan.listenup.client.download.DownloadFileManagerStorageAdapter
import com.calypsan.listenup.client.download.StorageSpaceProvider
import com.calypsan.listenup.client.presentation.settings.SettingsViewModel
import com.calypsan.listenup.client.presentation.storage.StorageViewModel
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorViewModel
import org.koin.dsl.module

/**
 * Auth and connection ViewModels.
 */
val authPresentationModule =
    module {
        factory {
            ServerSelectViewModel(
                serverRepository = get(),
                serverConfig = get(),
                instanceRepository = get(),
                errorBus = get(),
            )
        }
        factory { ServerConnectViewModel(serverConfig = get(), instanceRepository = get()) }
        factory {
            com.calypsan.listenup.client.presentation.auth.SetupViewModel(
                setupUseCase = get(),
                authSession = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.auth.LoginViewModel(
                loginUseCase = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.auth.RegisterViewModel(
                registerUseCase = get(),
            )
        }
        // PendingApprovalViewModel - takes (userId, email) parameters
        factory { params ->
            PendingApprovalViewModel(
                authSession = get(),
                registrationStatusStream = get(),
                userId = params.get<String>(0),
                email = params.get<String>(1),
            )
        }
        // InviteRegistrationViewModel - takes serverUrl and inviteCode as parameters
        factory { params ->
            InviteRegistrationViewModel(
                inviteRepository = get(),
                serverConfig = get(),
                serverUrl = params.get<String>(0),
                inviteCode = params.get<String>(1),
                errorBus = get(),
            )
        }
        // LibrarySetupViewModel for initial library configuration
        factory {
            com.calypsan.listenup.client.presentation.setup.LibrarySetupViewModel(
                libraryAdminRpcFactory = get(),
                errorBus = get(),
            )
        }
    }

/**
 * Admin ViewModels.
 */
val adminPresentationModule =
    module {
        single {
            AdminViewModel(
                instanceRepository = get(),
                loadUsersUseCase = get(),
                loadPendingUsersUseCase = get(),
                loadInvitesUseCase = get(),
                deleteUserUseCase = get(),
                revokeInviteUseCase = get(),
                approveUserUseCase = get(),
                denyUserUseCase = get(),
                setOpenRegistrationUseCase = get(),
                eventStreamRepository = get(),
            )
        }
        factory { CreateInviteViewModel(createInviteUseCase = get()) }
        factory {
            com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel(
                loadServerSettingsUseCase = get(),
                updateServerSettingsUseCase = get(),
                instanceRepository = get(),
                adminRepository = get(),
                errorBus = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.admin.AdminInboxViewModel(
                loadInboxBooksUseCase = get(),
                releaseBooksUseCase = get(),
                stageCollectionUseCase = get(),
                unstageCollectionUseCase = get(),
                eventStreamRepository = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.admin.AdminCollectionsViewModel(
                collectionRepository = get(),
                createCollectionUseCase = get(),
                deleteCollectionUseCase = get(),
                errorBus = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.admin.AdminCategoriesViewModel(
                genreRepository = get(),
                errorBus = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.browsegenre.BrowseGenreViewModel(
                genreRepository = get(),
                errorBus = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.unmappedgenres.UnmappedGenresViewModel(
                genreRepository = get(),
                errorBus = get(),
            )
        }
        // AdminCollectionDetailViewModel - takes collectionId as parameter
        factory { params ->
            com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailViewModel(
                collectionId = params.get<String>(0),
                collectionRepository = get(),
                loadCollectionBooksUseCase = get(),
                loadCollectionSharesUseCase = get(),
                updateCollectionNameUseCase = get(),
                removeBookFromCollectionUseCase = get(),
                shareCollectionUseCase = get(),
                removeCollectionShareUseCase = get(),
                getUsersForSharingUseCase = get(),
                errorBus = get(),
            )
        }
        // UserDetailViewModel - takes userId as parameter
        factory { params ->
            com.calypsan.listenup.client.presentation.admin.UserDetailViewModel(
                userId = params.get<String>(0),
                adminRepository = get(),
                errorBus = get(),
            )
        }
        // LibrarySettingsViewModel - takes libraryId as parameter
        factory { params ->
            com.calypsan.listenup.client.presentation.admin.LibrarySettingsViewModel(
                libraryId = params.get<String>(0),
                adminRepository = get(),
                errorBus = get(),
            )
        }
        // AdminBackupViewModel for backup management
        factory {
            com.calypsan.listenup.client.presentation.admin.AdminBackupViewModel(
                backupApi = get(),
                errorBus = get(),
            )
        }
        // RestoreBackupViewModel - takes backupId as parameter
        factory { params ->
            com.calypsan.listenup.client.presentation.admin.RestoreBackupViewModel(
                backupId = params.get<String>(0),
                backupApi = get(),
                syncRepository = get(),
                errorBus = get(),
            )
        }
        // ABSImportViewModel for Audiobookshelf import (legacy wizard)
        factory {
            com.calypsan.listenup.client.presentation.admin.ABSImportViewModel(
                backupApi = get(),
                searchApi = get(),
                absImportApi = get(),
                syncRepository = get(),
                errorBus = get(),
            )
        }

        // ABSImportHubViewModel for persistent/resumable imports
        single {
            com.calypsan.listenup.client.presentation.admin.ABSImportHubViewModel(
                absImportApi = get(),
                searchApi = get(),
                syncRepository = get(),
                errorBus = get(),
            )
        }
    }

/**
 * Library and core browsing ViewModels.
 */
val libraryPresentationModule =
    module {
        // Shared selection state - singleton so both ViewModels observe the same state
        single { LibrarySelectionManager() }

        // LibraryViewModel as singleton for preloading - starts loading Room data
        // immediately when injected at AppShell level, making Library instant
        single {
            LibraryViewModel(
                bookRepository = get(),
                seriesRepository = get(),
                contributorRepository = get(),
                playbackPositionRepository = get(),
                syncRepository = get(),
                authSession = get(),
                libraryPreferences = get(),
                syncStatusRepository = get(),
                selectionManager = get(),
            )
        }

        // LibraryActionsViewModel as singleton - shares selection state with LibraryViewModel
        single {
            LibraryActionsViewModel(
                selectionManager = get(),
                userRepository = get(),
                collectionRepository = get(),
                shelfRepository = get(),
                addBooksToCollectionUseCase = get(),
                refreshCollectionsUseCase = get(),
                addBooksToShelfUseCase = get(),
                createShelfUseCase = get(),
            )
        }

        factory {
            com.calypsan.listenup.client.presentation.search.SearchViewModel(
                searchRepository = get(),
                errorBus = get(),
            )
        }
    }

/**
 * Book and content detail ViewModels.
 */
val bookPresentationModule =
    module {
        factory {
            com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel(
                bookRepository = get(),
                tagRepository = get(),
                playbackPositionRepository = get(),
                userRepository = get(),
                shelfRepository = get(),
                addBooksToShelfUseCase = get(),
                createShelfUseCase = get(),
                errorBus = get(),
            )
        }
        factory { params ->
            com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel(
                repo = get(),
                bookId = params.get(),
            )
        }
        factory { params ->
            com.calypsan.listenup.client.presentation.bookdetail.BookListeningHistoryViewModel(
                repo = get(),
                bookId = params.get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.bookedit.BookEditViewModel(
                loadBookForEditUseCase = get(),
                updateBookUseCase = get(),
                contributorRepository = get(),
                seriesRepository = get(),
                imageStagingRepository = get(),
                errorBus = get(),
            )
        }
        // MetadataViewModel for Audible metadata search and matching
        factory {
            com.calypsan.listenup.client.presentation.metadata.MetadataViewModel(
                metadataRepository = get(),
                errorBus = get(),
            )
        }
    }

/**
 * Series ViewModels.
 */
val seriesPresentationModule =
    module {
        factory {
            com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel(
                seriesRepository = get<com.calypsan.listenup.client.domain.repository.SeriesRepository>(),
                imageRepository = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.seriesedit.SeriesEditViewModel(
                seriesRepository = get<com.calypsan.listenup.client.domain.repository.SeriesRepository>(),
                updateSeriesUseCase = get(),
                imageRepository = get(),
                imageStagingRepository = get(),
                seriesEditRepository = get<com.calypsan.listenup.client.domain.repository.SeriesEditRepository>(),
                seriesDao = get(),
                errorBus = get(),
            )
        }
    }

/**
 * Contributor ViewModels.
 */
val contributorPresentationModule =
    module {
        factory {
            com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailViewModel(
                contributorRepository = get<com.calypsan.listenup.client.domain.repository.ContributorRepository>(),
                playbackPositionRepository = get(),
                deleteContributorUseCase = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksViewModel(
                contributorRepository = get<com.calypsan.listenup.client.domain.repository.ContributorRepository>(),
                playbackPositionRepository = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.contributoredit.ContributorEditViewModel(
                contributorRepository = get<com.calypsan.listenup.client.domain.repository.ContributorRepository>(),
                updateContributorUseCase = get(),
                imageRepository = get(),
                contributorEditRepository = get<com.calypsan.listenup.client.domain.repository.ContributorEditRepository>(),
                contributorAliasDao = get(),
                contributorDao = get(),
                errorBus = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataViewModel(
                contributorRepository = get<com.calypsan.listenup.client.domain.repository.ContributorRepository>(),
                metadataRepository = get(),
                applyContributorMetadataUseCase = get(),
                errorBus = get(),
            )
        }
    }

/**
 * Discover and social ViewModels.
 */
val discoverPresentationModule =
    module {
        factory {
            com.calypsan.listenup.client.presentation.home.HomeViewModel(
                homeRepository = get(),
                userRepository = get(),
                shelfRepository = get(),
                syncRepository = get(),
                errorBus = get(),
            )
        }
        // HomeStatsViewModel for home screen stats section (observes local stats)
        factory { HomeStatsViewModel(statsRepository = get()) }
        factory {
            com.calypsan.listenup.client.presentation.discover.DiscoverViewModel(
                bookRepository = get(),
                activeSessionRepository = get(),
                authSession = get(),
                shelfRepository = get(),
                errorBus = get(),
            )
        }
        // LeaderboardViewModel for discover screen leaderboard
        factory { LeaderboardViewModel(repo = get()) }
        // ActivityFeedViewModel for discover screen activity feed
        factory { ActivityFeedViewModel(activityRepository = get(), fetchActivitiesUseCase = get()) }
    }

/**
 * Tag and shelf ViewModels.
 */
val tagShelfPresentationModule =
    module {
        factory {
            com.calypsan.listenup.client.presentation.tagdetail.TagDetailViewModel(
                tagRepository = get(),
                bookRepository = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.shelf.ShelfDetailViewModel(
                loadShelfDetailUseCase = get(),
                removeBookFromShelfUseCase = get(),
                userRepository = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.shelf.CreateEditShelfViewModel(
                createShelfUseCase = get(),
                updateShelfUseCase = get(),
                deleteShelfUseCase = get(),
                shelfRepository = get(),
                errorBus = get(),
            )
        }
    }

/**
 * User profile ViewModels.
 */
val profilePresentationModule =
    module {
        // UserProfileViewModel for viewing user profiles
        factory {
            com.calypsan.listenup.client.presentation.profile.UserProfileViewModel(
                loadUserProfileUseCase = get(),
                userRepository = get(),
                imageRepository = get(),
                errorBus = get(),
            )
        }
        // EditProfileViewModel for editing own profile
        factory {
            com.calypsan.listenup.client.presentation.profile.EditProfileViewModel(
                profileEditRepository = get(),
                userRepository = get(),
                imageRepository = get(),
            )
        }
    }

/**
 * Settings and sync indicator ViewModels.
 */
val settingsPresentationModule =
    module {
        factory {
            SettingsViewModel(
                libraryPreferences = get(),
                playbackPreferences = get(),
                localPreferences = get(),
                userPreferencesRepository = get(),
                instanceRepository = get(),
                serverConfig = get(),
                authSession = get(),
            )
        }
        // SyncIndicatorViewModel as singleton for app-wide sync status
        single { SyncIndicatorViewModel(pendingOperationRepository = get(), syncRepository = get()) }
        // StorageViewModel for storage management screen
        factory<StorageSpaceProvider> { DownloadFileManagerStorageAdapter(get<DownloadFileManager>()) }
        factory {
            StorageViewModel(
                downloadRepository = get(),
                downloadService = get(),
                storageSpaceProvider = get(),
                errorBus = get(),
            )
        }
    }

/**
 * App startup / navigation initialisation ViewModel.
 */
val startupPresentationModule =
    module {
        factory {
            com.calypsan.listenup.client.presentation.startup.AppStartupViewModel(
                userRepository = get(),
                libraryAdminRpcFactory = get(),
            )
        }
    }

/**
 * All presentation modules combined.
 */
val allPresentationModules =
    listOf(
        authPresentationModule,
        adminPresentationModule,
        libraryPresentationModule,
        bookPresentationModule,
        seriesPresentationModule,
        contributorPresentationModule,
        discoverPresentationModule,
        tagShelfPresentationModule,
        profilePresentationModule,
        settingsPresentationModule,
        startupPresentationModule,
    )
