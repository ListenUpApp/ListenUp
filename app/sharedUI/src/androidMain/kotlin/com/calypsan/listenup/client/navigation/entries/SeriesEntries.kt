package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.ContributorDetail
import com.calypsan.listenup.client.navigation.SeriesDetail
import com.calypsan.listenup.client.navigation.SeriesEdit

/** Series navigation entries. */
internal fun EntryProviderScope<NavKey>.seriesEntries(backStack: NavBackStack<NavKey>) {
    entry<SeriesDetail> { args ->
        com.calypsan.listenup.client.features.seriesdetail.SeriesDetailScreen(
            seriesId = args.seriesId,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onBookClick = { bookId ->
                backStack.add(BookDetail(bookId))
            },
            onEditClick = { seriesId ->
                backStack.add(SeriesEdit(seriesId))
            },
            onContributorClick = { contributorId ->
                backStack.add(ContributorDetail(contributorId))
            },
        )
    }
    entry<SeriesEdit> { args ->
        com.calypsan.listenup.client.features.seriesedit.SeriesEditScreen(
            seriesId = args.seriesId,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onSaveSuccess = {
                // Navigate back after successful save
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
}
