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
            onMergedInto = { survivingSeriesId ->
                // Drop BOTH the editor and the detail page beneath it: the merge soft-deleted the
                // series they describe, so popping only the editor lands on an empty shell that
                // takes another Back to escape. Land on the survivor instead.
                backStack.removeAt(backStack.lastIndex)
                if (backStack.lastOrNull() is SeriesDetail) {
                    backStack.removeAt(backStack.lastIndex)
                }
                backStack.add(SeriesDetail(survivingSeriesId))
            },
        )
    }
}
