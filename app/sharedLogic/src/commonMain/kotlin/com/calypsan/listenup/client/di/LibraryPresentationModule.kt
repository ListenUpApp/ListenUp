package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.library.LibraryViewModel
import com.calypsan.listenup.client.presentation.search.SearchViewModel
import com.calypsan.listenup.client.presentation.search.SeeAllSearchViewModel
import com.calypsan.listenup.client.presentation.sync.ManualSyncRefresh
import org.koin.dsl.module

/**
 * Library and core browsing ViewModels.
 */
internal val libraryPresentationModule =
    module {
        // One shared "sync now" (single: its state is the app's): the sync indicator's Refresh and the Library's
        // pull-to-refresh report through it, so a failed pull is what the indicator says.
        single { ManualSyncRefresh(syncRepository = get(), errorBus = get()) }

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
                bookRatingRepository = get(),
                manualSyncRefresh = get(),
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
            SearchViewModel(
                searchRepository = get(),
                errorBus = get(),
            )
        }

        factory {
            SeeAllSearchViewModel(
                searchRepository = get(),
                errorBus = get(),
            )
        }
    }
