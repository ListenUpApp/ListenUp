package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.ContributorDetail
import com.calypsan.listenup.client.navigation.ReadingOrders
import com.calypsan.listenup.client.navigation.SeriesDetail
import com.calypsan.listenup.client.navigation.SeriesEdit
import com.calypsan.listenup.client.navigation.StoryWorldHub
import com.calypsan.listenup.client.navigation.TagDetail

/** Series and tag navigation entries. */
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
            onStoryWorldClick = { seriesId ->
                backStack.add(StoryWorldHub(seriesId = seriesId))
            },
            onReadingOrdersClick = { seriesId ->
                backStack.add(ReadingOrders(seriesId = seriesId))
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
    entry<TagDetail> { args ->
        com.calypsan.listenup.client.features.tagdetail.TagDetailScreen(
            tagId = args.tagId,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onBookClick = { bookId ->
                backStack.add(BookDetail(bookId))
            },
        )
    }
}
