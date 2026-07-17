package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.features.browsefacet.FacetBooksScreen
import com.calypsan.listenup.client.features.documentviewer.DocumentViewerScreen
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.BookEdit
import com.calypsan.listenup.client.navigation.BookReaders
import com.calypsan.listenup.client.navigation.BrowseFacet
import com.calypsan.listenup.client.navigation.BrowseGenre
import com.calypsan.listenup.client.navigation.ContributorDetail
import com.calypsan.listenup.client.navigation.DocumentViewer
import com.calypsan.listenup.client.navigation.MatchPreview
import com.calypsan.listenup.client.navigation.MetadataSearch
import com.calypsan.listenup.client.navigation.SeriesDetail
import com.calypsan.listenup.client.navigation.UserProfile
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetViewModel
import org.koin.compose.viewmodel.koinViewModel

/** Book navigation entries. */
internal fun EntryProviderScope<NavKey>.bookEntries(backStack: NavBackStack<NavKey>) {
    entry<BookDetail> { args ->
        com.calypsan.listenup.client.features.bookdetail.BookDetailScreen(
            bookId = args.bookId,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onEditClick = { bookId ->
                backStack.add(BookEdit(bookId))
            },
            onMetadataSearchClick = { bookId ->
                backStack.add(MetadataSearch(bookId))
            },
            onSeriesClick = { seriesId ->
                backStack.add(SeriesDetail(seriesId))
            },
            onContributorClick = { contributorId ->
                backStack.add(ContributorDetail(contributorId))
            },
            onGenreClick = { genreId ->
                backStack.add(BrowseGenre(genreId = genreId))
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
            onSeeAllReaders = { id ->
                backStack.add(BookReaders(id))
            },
            onOpenDocumentViewer = { localPath ->
                backStack.add(DocumentViewer(localPath))
            },
        )
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
    entry<MetadataSearch> { args ->
        com.calypsan.listenup.client.features.metadata.MetadataSearchRoute(
            bookId = args.bookId,
            onResultSelected = { asin, region ->
                backStack.add(MatchPreview(args.bookId, asin, region))
            },
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    entry<MatchPreview> { args ->
        com.calypsan.listenup.client.features.metadata.MatchPreviewRoute(
            bookId = args.bookId,
            asin = args.asin,
            region = args.region,
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
            onApplySuccess = {
                // Navigate back to book detail after successful apply
                // Pop both MatchPreview and MetadataSearch
                backStack.removeAt(backStack.lastIndex)
                if (backStack.lastOrNull() is MetadataSearch) {
                    backStack.removeAt(backStack.lastIndex)
                }
            },
        )
    }
}
