package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.CreateShelf
import com.calypsan.listenup.client.navigation.EditProfile
import com.calypsan.listenup.client.navigation.ShelfDetail
import com.calypsan.listenup.client.navigation.UserProfile

/** User profile navigation entries. */
internal fun EntryProviderScope<NavKey>.profileEntries(
    backStack: NavBackStack<NavKey>,
    profileRefreshKey: Int,
    onProfileRefreshed: () -> Unit,
) {
    entry<UserProfile> { args ->
        com.calypsan.listenup.client.features.profile.UserProfileScreen(
            userId = args.userId,
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
            onEditClick = {
                backStack.add(EditProfile)
            },
            onBookClick = { bookId ->
                backStack.add(BookDetail(bookId))
            },
            onShelfClick = { shelfId ->
                backStack.add(ShelfDetail(shelfId))
            },
            onCreateShelfClick = {
                backStack.add(CreateShelf)
            },
            refreshKey = profileRefreshKey,
        )
    }
    entry<EditProfile> {
        com.calypsan.listenup.client.features.profile.EditProfileScreen(
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
            onProfileUpdated = {
                onProfileRefreshed()
            },
        )
    }
}
