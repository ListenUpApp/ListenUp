package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import com.calypsan.listenup.client.presentation.admin.CreateInviteViewModel
import com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel
import com.calypsan.listenup.client.presentation.connection.ConnectionHealthViewModel
import com.calypsan.listenup.client.presentation.connect.ServerSelectViewModel
import com.calypsan.listenup.client.presentation.discover.ActivityFeedViewModel
import com.calypsan.listenup.client.presentation.discover.LeaderboardViewModel
import com.calypsan.listenup.client.presentation.home.HomeStatsViewModel
import com.calypsan.listenup.client.presentation.library.LibraryViewModel
import com.calypsan.listenup.client.download.DownloadFileManager
import com.calypsan.listenup.client.download.DownloadFileManagerStorageAdapter
import com.calypsan.listenup.client.download.StorageSpaceProvider
import com.calypsan.listenup.client.presentation.settings.DevicesViewModel
import com.calypsan.listenup.client.presentation.settings.SettingsViewModel
import com.calypsan.listenup.client.presentation.storage.StorageViewModel
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorViewModel
import org.koin.dsl.module

/** Koin qualifier for the application-lifetime [kotlinx.coroutines.CoroutineScope] (`appCoreModule`). */
private const val APP_SCOPE = "appScope"

/**
 * Auth and connection ViewModels.
 */
internal val authPresentationModule =
    module {
        factory {
            ServerSelectViewModel(
                serverRepository = get(),
                serverConfig = get(),
                instanceRepository = get(),
                errorBus = get(),
                // App-lifetime scope: selecting a server flips the global auth state, which tears this
                // screen (and its viewModelScope) down mid-activation. The activation must outlive it.
                appScope =
                    get(
                        qualifier =
                            org.koin.core.qualifier
                                .named(APP_SCOPE),
                    ),
            )
        }
        factory {
            ServerConnectViewModel(
                serverConfig = get(),
                instanceRepository = get(),
                // App-lifetime scope: saving the verified URL flips the global auth state, tearing this
                // screen (and its viewModelScope) down mid-activation — the work must outlive it.
                appScope =
                    get(
                        qualifier =
                            org.koin.core.qualifier
                                .named(APP_SCOPE),
                    ),
            )
        }
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
        factory {
            com.calypsan.listenup.client.presentation.invite.ClaimInviteViewModel(
                repository = get(),
                serverConfig = get(),
                instanceRepository = get(),
            )
        }
        // LibrarySetupViewModel for initial library configuration
        factory {
            com.calypsan.listenup.client.presentation.setup.LibrarySetupViewModel(
                libraryAdminChannel = rpcChannel<LibraryAdminService>(),
                errorBus = get(),
                // App-lifetime scope: the initial scan triggered after onboarding adds its
                // folders must outlive this wizard (torn down when onboarding finishes and we
                // navigate to the Shell), so it can't ride viewModelScope.
                appScope =
                    get(
                        qualifier =
                            org.koin.core.qualifier
                                .named(APP_SCOPE),
                    ),
            )
        }
    }

/**
 * Admin ViewModels.
 */
internal val adminPresentationModule =
    module {
        single {
            AdminViewModel(
                getRegistrationPolicyUseCase = get(),
                loadInvitesUseCase = get(),
                deleteUserUseCase = get(),
                revokeInviteUseCase = get(),
                approveUserUseCase = get(),
                denyUserUseCase = get(),
                setRegistrationPolicyUseCase = get(),
                adminRepository = get(),
            )
        }
        factory { CreateInviteViewModel(createInviteUseCase = get()) }
        factory {
            com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel(
                loadServerSettingsUseCase = get(),
                updateServerSettingsUseCase = get(),
                errorBus = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.admin.AdminInboxViewModel(
                inboxRepository = get(),
                libraryRepository = get(),
                eventStreamRepository = get(),
                bookDao = get(),
                imageStorage = get(),
                errorBus = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.admin.AdminCollectionsViewModel(
                collectionRepository = get(),
                libraryRepository = get(),
                errorBus = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.admin.AdminCategoriesViewModel(
                genreRepository = get(),
                errorBus = get(),
            )
        }
        // AdminCollectionDetailViewModel - takes collectionId as parameter
        factory { params ->
            com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailViewModel(
                collectionId = params.get<String>(0),
                collectionRepository = get(),
                adminRepository = get(),
                userRepository = get(),
                userProfileRepository = get(),
                bookDao = get(),
                searchRepository = get(),
                imageStorage = get(),
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
        // LibrarySettingsViewModel — operates on THE singleton library (no id param)
        factory {
            com.calypsan.listenup.client.presentation.admin.LibrarySettingsViewModel(
                adminRepository = get(),
                errorBus = get(),
            )
        }
        // AdminBackupViewModel for backup management
        factory {
            com.calypsan.listenup.client.presentation.admin.AdminBackupViewModel(
                backupRepository = get(),
                errorBus = get(),
            )
        }
        // RestoreFromFileViewModel - pick + upload a .listenup.zip, then hand off to restore
        factory {
            com.calypsan.listenup.client.presentation.admin.RestoreFromFileViewModel(
                backupRepository = get(),
                errorBus = get(),
            )
        }
        // RestoreBackupViewModel - takes backupId as parameter
        factory { params ->
            com.calypsan.listenup.client.presentation.admin.RestoreBackupViewModel(
                backupId = params.get<String>(0),
                backupRepository = get(),
                syncRepository = get(),
                errorBus = get(),
            )
        }
        // ABSImportHubViewModel — inline staged-import list (backed by the new ImportService stack)
        single {
            com.calypsan.listenup.client.presentation.admin.ABSImportHubViewModel(
                importRepository = get(),
                errorBus = get(),
            )
        }
        // ImportFlowViewModel for the rebuilt single-screen ABS import flow
        factory {
            com.calypsan.listenup.client.presentation.admin.imports.ImportFlowViewModel(
                importRepository = get(),
                errorBus = get(),
                syncRepository = get(),
                adminRepository = get(),
                searchRepository = get(),
            )
        }
    }

/**
 * Library and core browsing ViewModels.
 */
internal val libraryPresentationModule =
    module {
        // factory (NOT single): koinViewModel() then scopes a fresh instance to each
        // ViewModelStore. A singleton VM is fatal here — when the first owning store is cleared
        // (e.g. onboarding's backStack.clear()), onCleared() cancels the singleton's viewModelScope,
        // and the re-served same instance has uiState.stateIn(deadScope) pinned at Loading forever.
        // Preloading still works: the AppShell's koinViewModel() creates it for the shell's store and
        // the Library screen reuses that same store-scoped instance. (Matches SearchViewModel below
        // and sharedUI rule 8; viewModelOf isn't on sharedLogic's classpath, so factory is the
        // commonMain-equivalent.)
        factory {
            LibraryViewModel(
                bookRepository = get(),
                seriesRepository = get(),
                contributorRepository = get(),
                playbackPositionRepository = get(),
                syncRepository = get(),
                authSession = get(),
                libraryPreferences = get(),
                syncStatusRepository = get(),
            )
        }

        // Per-screen multi-select VM. Owns its own selection state (no shared manager), so it is a
        // plain factory scoped to the consuming ViewModelStore like the Library VM above.
        factory {
            BookMultiSelectViewModel(
                userRepository = get(),
                collectionRepository = get(),
                shelfRepository = get(),
                addBooksToShelfUseCase = get(),
                addBooksToCollectionUseCase = get(),
                createShelfUseCase = get(),
                createCollectionUseCase = get(),
                errorBus = get(),
            )
        }

        factory {
            com.calypsan.listenup.client.presentation.search.SearchViewModel(
                searchRepository = get(),
                errorBus = get(),
            )
        }

        factory {
            com.calypsan.listenup.client.presentation.search.SeeAllSearchViewModel(
                searchRepository = get(),
                errorBus = get(),
            )
        }
    }

/**
 * Book and content detail ViewModels.
 */
internal val bookPresentationModule =
    module {
        factory {
            com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel(
                bookRepository = get(),
                tagRepository = get(),
                playbackPositionRepository = get(),
                userRepository = get(),
                shelfRepository = get(),
                collectionRepository = get(),
                addBooksToShelfUseCase = get(),
                createShelfUseCase = get(),
                errorBus = get(),
                bookAvailability = get(),
                serverReachability = get(),
                documentRepository = get(),
            )
        }
        factory { params ->
            com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel(
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
                collectionRepository = get(),
                bookEditRepository = get(),
                userRepository = get(),
                imageStagingRepository = get(),
                errorBus = get(),
            )
        }
        // MetadataViewModel for Audible metadata search and matching
        factory {
            com.calypsan.listenup.client.presentation.metadata.MetadataViewModel(
                metadataRepository = get(),
                bookRepository = get(),
                genreRepository = get(),
                moodRepository = get(),
                tagRepository = get(),
                errorBus = get(),
            )
        }
    }

/**
 * Series ViewModels.
 */
internal val seriesPresentationModule =
    module {
        factory {
            com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailViewModel(
                seriesRepository = get<com.calypsan.listenup.client.domain.repository.SeriesRepository>(),
                imageRepository = get(),
                playbackPositionRepository = get(),
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
internal val contributorPresentationModule =
    module {
        factory {
            com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailViewModel(
                contributorRepository = get<com.calypsan.listenup.client.domain.repository.ContributorRepository>(),
                playbackPositionRepository = get(),
                seriesRepository = get<com.calypsan.listenup.client.domain.repository.SeriesRepository>(),
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
internal val discoverPresentationModule =
    module {
        factory {
            com.calypsan.listenup.client.presentation.home.HomeViewModel(
                homeRepository = get(),
                userRepository = get(),
                shelfRepository = get(),
                syncRepository = get(),
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
        factory {
            ActivityFeedViewModel(
                activityRepository = get(),
                syncRepository = get(),
            )
        }
    }

/**
 * Tag and shelf ViewModels.
 */
internal val tagShelfPresentationModule =
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
                reorderShelfBooksUseCase = get(),
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
 * Facet-browse ViewModels — "show every book matching one categorization" (genre, tag, or mood).
 * Reached from the genre/tag/mood chips on Book Detail; each is a pure read over the library, so
 * these belong with the browsing surfaces, not the admin console where they originally landed.
 */
internal val browsePresentationModule =
    module {
        factory {
            com.calypsan.listenup.client.presentation.browsegenre.BrowseGenreViewModel(
                genreRepository = get(),
                errorBus = get(),
            )
        }
        factory {
            com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetViewModel(
                tagRepository = get(),
                moodRepository = get(),
                bookRepository = get(),
            )
        }
    }

/**
 * User profile ViewModels.
 */
internal val profilePresentationModule =
    module {
        // UserProfileViewModel for viewing user profiles
        factory {
            com.calypsan.listenup.client.presentation.profile.UserProfileViewModel(
                publicProfileDao = get(),
                shelfRepository = get(),
                userRepository = get(),
            )
        }
        // EditProfileViewModel for editing own profile
        factory {
            com.calypsan.listenup.client.presentation.profile.EditProfileViewModel(
                profileEditRepository = get(),
                userRepository = get(),
                userProfileRepository = get(),
            )
        }
    }

/**
 * Settings and sync indicator ViewModels.
 */
internal val settingsPresentationModule =
    module {
        factory {
            SettingsViewModel(
                libraryPreferences = get(),
                playbackPreferences = get(),
                localPreferences = get(),
                userPreferencesRepository = get(),
                instanceRepository = get(),
                serverConfig = get(),
                logoutUseCase = get<com.calypsan.listenup.client.domain.usecase.auth.LogoutUseCase>(),
            )
        }
        // DevicesViewModel for the Devices (active sessions) screen
        factory { DevicesViewModel(authRepository = get()) }
        // factory (NOT single) — same cancelled-viewModelScope hazard as the Library VMs above.
        factory { SyncIndicatorViewModel(pendingOperationRepository = get(), syncRepository = get()) }
        // Shell connection-health banner VM, projecting ConnectionHealthStore.state.
        factory { ConnectionHealthViewModel(healthStore = get(), serverReachability = get()) }
        // StorageViewModel for storage management screen
        factory<StorageSpaceProvider> { DownloadFileManagerStorageAdapter(get<DownloadFileManager>()) }
        factory {
            StorageViewModel(
                downloadRepository = get(),
                downloadService = get(),
                storageSpaceProvider = get(),
                errorBus = get(),
                // The concrete PlaybackManager implements PlaybackStateProvider (same narrowing as
                // AuthModule's LogoutUseCase wiring) — used to refuse deleting the playing book (B9).
                playbackStateProvider = get<com.calypsan.listenup.client.playback.PlaybackManager>(),
            )
        }
    }

/**
 * App startup / navigation initialisation ViewModel.
 */
internal val startupPresentationModule =
    module {
        factory {
            com.calypsan.listenup.client.presentation.startup.AppStartupViewModel(
                userRepository = get(),
                libraryAdminChannel = rpcChannel<LibraryAdminService>(),
                authSession = get(),
                syncRepository = get(),
            )
        }
    }

/**
 * All presentation modules combined.
 */
internal val allPresentationModules =
    listOf(
        authPresentationModule,
        adminPresentationModule,
        libraryPresentationModule,
        bookPresentationModule,
        seriesPresentationModule,
        contributorPresentationModule,
        discoverPresentationModule,
        tagShelfPresentationModule,
        browsePresentationModule,
        profilePresentationModule,
        settingsPresentationModule,
        startupPresentationModule,
    )
