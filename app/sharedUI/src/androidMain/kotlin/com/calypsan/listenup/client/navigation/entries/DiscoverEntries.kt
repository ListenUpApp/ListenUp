package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.DiscoverActivity
import com.calypsan.listenup.client.navigation.ShelfDetail
import com.calypsan.listenup.client.navigation.UserProfile
import com.calypsan.listenup.client.navigation.popFrom

/** Discover's own screens beyond the tab: See all activity (X33). */
internal fun EntryProviderScope<NavKey>.discoverEntries(backStack: NavBackStack<NavKey>) {
    entry<DiscoverActivity> {
        com.calypsan.listenup.client.features.discover.ActivityFeedScreen(
            onBack = { backStack.popFrom(DiscoverActivity) },
            onBookClick = { bookId -> backStack.add(BookDetail(bookId)) },
            onShelfClick = { shelfId -> backStack.add(ShelfDetail(shelfId)) },
            onUserClick = { userId -> backStack.add(UserProfile(userId)) },
        )
    }
}
