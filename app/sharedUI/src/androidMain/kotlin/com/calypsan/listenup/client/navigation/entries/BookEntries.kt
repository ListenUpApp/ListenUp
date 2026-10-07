package com.calypsan.listenup.client.navigation.entries

import androidx.compose.material3.SnackbarHostState
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.features.browsefacet.FacetBooksScreen
import com.calypsan.listenup.client.design.transitions.HeroEntry
import com.calypsan.listenup.client.design.transitions.heroEntryTransitions
import com.calypsan.listenup.client.features.bulkedit.PendingSelectionExit
import com.calypsan.listenup.client.features.bulkedit.bulkEditAppliedMessage
import com.calypsan.listenup.client.features.documentviewer.DocumentViewerScreen
import com.calypsan.listenup.client.features.genredestination.GenreDestinationScreen
import com.calypsan.listenup.client.navigation.AdminCollectionDetail
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.BookEdit
import com.calypsan.listenup.client.navigation.BookMatchDetails
import com.calypsan.listenup.client.navigation.BookReaders
import com.calypsan.listenup.client.navigation.HardcoverMatch
import com.calypsan.listenup.client.navigation.BulkEdit
import com.calypsan.listenup.client.navigation.ChapterEditor
import com.calypsan.listenup.client.navigation.BrowseFacet
import com.calypsan.listenup.client.navigation.ContributorDetail
import com.calypsan.listenup.client.navigation.DocumentViewer
import com.calypsan.listenup.client.navigation.GenreDestination
import com.calypsan.listenup.client.navigation.ListDetailScene
import com.calypsan.listenup.client.navigation.SeriesDetail
import com.calypsan.listenup.client.navigation.UserProfile
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetViewModel
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Book navigation entries.
 *
 * [scope] and [snackbarHostState] belong to the shell, not to any entry: the bulk editor
 * announces its result *after* popping itself, so the coroutine that raises the snackbar has to
 * outlive the screen that asked for it.
 */
internal fun EntryProviderScope<NavKey>.bookEntries(
    backStack: NavBackStack<NavKey>,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    pendingSelectionExit: PendingSelectionExit,
) {
    // The detail pane: opened from a list pane on a wide window, it sits beside that list.
    entry<BookDetail>(metadata = heroEntryTransitions + ListDetailScene.detailPane()) { args ->
        HeroEntry {
            com.calypsan.listenup.client.features.bookdetail.BookDetailScreen(
                bookId = args.bookId,
                onBackClick = {
                    backStack.removeAt(backStack.lastIndex)
                },
                onEditClick = { bookId ->
                    backStack.add(BookEdit(bookId))
                },
                onMatchDetailsClick = { bookId ->
                    backStack.add(BookMatchDetails(bookId))
                },
                onSeriesClick = { seriesId ->
                    backStack.add(SeriesDetail(seriesId))
                },
                onContributorClick = { contributorId ->
                    backStack.add(ContributorDetail(contributorId))
                },
                onGenreClick = { genreId ->
                    backStack.add(GenreDestination(genreId = genreId))
                },
                onTagClick = { tagId, tagName ->
                    backStack.add(BrowseFacet(kind = FacetKind.Tag, facetId = tagId, facetName = tagName))
                },
                onMoodClick = { moodId, moodName ->
                    backStack.add(BrowseFacet(kind = FacetKind.Mood, facetId = moodId, facetName = moodName))
                },
                onUserProfileClick = { userId ->
                    backStack.add(UserProfile(userId))
                },
                onSeeAllReaders = { id -> backStack.add(BookReaders(id)) },
                onFindHardcoverMatch = { id -> backStack.add(HardcoverMatch(id)) },
                onCollectionClick = { id -> backStack.add(AdminCollectionDetail(id)) },
                onOpenDocumentViewer = { localPath ->
                    backStack.add(DocumentViewer(localPath))
                },
                onEditChaptersClick = { id ->
                    backStack.add(ChapterEditor(id))
                },
            )
        }
    }
    entry<DocumentViewer> { args ->
        DocumentViewerScreen(
            path = args.localPath,
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    entry<BrowseFacet> { args ->
        val viewModel: BrowseFacetViewModel = koinViewModel()
        FacetBooksScreen(
            kind = args.kind,
            facetId = args.facetId,
            facetName = args.facetName,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onBookClick = { bookId ->
                backStack.add(BookDetail(bookId))
            },
            viewModel = viewModel,
        )
    }
    genreDestinationEntry(backStack)
    entry<BookReaders> { args ->
        com.calypsan.listenup.client.features.bookreaders.BookReadersScreen(
            bookId = args.bookId,
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
            onUserClick = { userId ->
                backStack.add(UserProfile(userId))
            },
        )
    }
    entry<BookEdit> { args ->
        com.calypsan.listenup.client.features.bookedit.BookEditScreen(
            bookId = args.bookId,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onSaveSuccess = {
                // Navigate back after successful save
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    chapterEditorEntry(backStack)
    bulkEditEntry(backStack, scope, snackbarHostState, pendingSelectionExit)
    entry<BookMatchDetails> { args ->
        com.calypsan.listenup.client.features.match.BookMatchRoute(
            bookId = args.bookId,
            onBack = { backStack.removeAt(backStack.lastIndex) },
            onApplied = { backStack.returnToBookDetailAfterMatch(args) },
        )
    }
}

/**
 * Leaves Match details for Book Detail, which shows the receipt: a pop when Book Detail opened it, otherwise
 * (the admin inbox's held-book triage) Book Detail takes the match's place on the stack.
 */
internal fun NavBackStack<NavKey>.returnToBookDetailAfterMatch(match: BookMatchDetails) {
    removeAt(lastIndex)
    if ((lastOrNull() as? BookDetail)?.bookId != match.bookId) add(BookDetail(match.bookId))
}

/** The chapter editor entry, split out to keep [bookEntries] within the method-length limit. */
private fun EntryProviderScope<NavKey>.chapterEditorEntry(backStack: NavBackStack<NavKey>) {
    entry<ChapterEditor> { args ->
        com.calypsan.listenup.client.features.chaptereditor.ChapterEditorScreen(
            bookId = args.bookId,
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
}

/**
 * The bulk metadata editor entry, split out to keep [bookEntries] within the method-length limit.
 *
 * Both exits pop: leaving without applying and leaving after a successful apply land back on the
 * screen the selection was made from, where the books are already up to date — the repositories
 * write Room-first, so the grid behind has changed by the time this pops.
 *
 * A successful apply also ends the selection it was opened over — the books have already
 * changed, and leaving them standing and armed invites a second, accidental edit of the same forty.
 *
 * A successful apply also says so. The grid it returns to shows covers and titles, so a publisher
 * written to forty books changes nothing the eye can catch — and a write nobody can see is a write
 * nobody can trust. The snackbar rides the shell's [scope] because this entry is gone by then.
 */
private fun EntryProviderScope<NavKey>.bulkEditEntry(
    backStack: NavBackStack<NavKey>,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    pendingSelectionExit: PendingSelectionExit,
) {
    entry<BulkEdit> { args ->
        com.calypsan.listenup.client.features.bulkedit.BulkEditScreen(
            bookIds = args.bookIds,
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
            onApplied = { changedCount ->
                pendingSelectionExit.fireAndDisarm()
                backStack.removeAt(backStack.lastIndex)
                if (changedCount > 0) {
                    scope.launch {
                        snackbarHostState.showSnackbar(bulkEditAppliedMessage(changedCount))
                    }
                }
            },
        )
    }
}

/** The genre destination page entry, split out to keep [bookEntries] within the method-length limit. */
private fun EntryProviderScope<NavKey>.genreDestinationEntry(backStack: NavBackStack<NavKey>) {
    entry<GenreDestination> { args ->
        val viewModel: GenreDestinationViewModel = koinViewModel()
        GenreDestinationScreen(
            genreId = args.genreId,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onBookClick = { bookId ->
                backStack.add(BookDetail(bookId))
            },
            onGenreClick = { genreId ->
                backStack.add(GenreDestination(genreId = genreId))
            },
            viewModel = viewModel,
        )
    }
}
