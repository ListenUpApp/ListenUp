package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.ContributorBooks
import com.calypsan.listenup.client.navigation.ContributorDetail
import com.calypsan.listenup.client.navigation.ContributorEdit
import com.calypsan.listenup.client.navigation.ContributorMetadataPreview
import com.calypsan.listenup.client.navigation.ContributorMetadataSearch

/** Contributor navigation entries. */
internal fun EntryProviderScope<NavKey>.contributorEntries(backStack: NavBackStack<NavKey>) {
    entry<ContributorDetail> { args ->
        com.calypsan.listenup.client.features.contributordetail.ContributorDetailScreen(
            contributorId = args.contributorId,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onBookClick = { bookId ->
                backStack.add(BookDetail(bookId))
            },
            onEditClick = { contributorId ->
                backStack.add(ContributorEdit(contributorId))
            },
            onViewAllClick = { contributorId, role ->
                backStack.add(ContributorBooks(contributorId, role))
            },
            onMetadataClick = { contributorId ->
                backStack.add(ContributorMetadataSearch(contributorId))
            },
        )
    }
    entry<ContributorEdit> { args ->
        com.calypsan.listenup.client.features.contributoredit.ContributorEditScreen(
            contributorId = args.contributorId,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onSaveSuccess = {
                // Navigate back after successful save
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    entry<ContributorBooks> { args ->
        com.calypsan.listenup.client.features.contributordetail.ContributorBooksScreen(
            contributorId = args.contributorId,
            role = args.role,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onBookClick = { bookId ->
                backStack.add(BookDetail(bookId))
            },
        )
    }
    entry<ContributorMetadataSearch> { args ->
        com.calypsan.listenup.client.features.contributormetadata.ContributorMetadataSearchRoute(
            contributorId = args.contributorId,
            onCandidateSelected = { asin, region ->
                backStack.add(ContributorMetadataPreview(args.contributorId, asin, region))
            },
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    entry<ContributorMetadataPreview> { args ->
        com.calypsan.listenup.client.features.contributormetadata.ContributorMetadataPreviewRoute(
            contributorId = args.contributorId,
            asin = args.asin,
            region = args.region,
            onApplySuccess = {
                // Pop both preview and search to go back to contributor detail
                backStack.removeAt(backStack.lastIndex)
                backStack.removeAt(backStack.lastIndex)
            },
            onChangeMatch = {
                // Pop preview to go back to search
                backStack.removeAt(backStack.lastIndex)
            },
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
}
