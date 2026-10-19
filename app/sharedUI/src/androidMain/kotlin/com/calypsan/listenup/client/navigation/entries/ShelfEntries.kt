package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.navigation.popFrom
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.CreateShelf
import com.calypsan.listenup.client.navigation.MyShelves
import com.calypsan.listenup.client.navigation.ShelfDetail
import com.calypsan.listenup.client.navigation.ShelfEdit

/** Shelf navigation entries. */
internal fun EntryProviderScope<NavKey>.shelfEntries(backStack: NavBackStack<NavKey>) {
    entry<MyShelves> {
        com.calypsan.listenup.client.features.shelf.MyShelvesScreen(
            onBack = { backStack.popFrom(MyShelves) },
            onOpenShelf = { shelfId -> backStack.add(ShelfDetail(shelfId)) },
            onCreateShelf = { backStack.add(CreateShelf) },
        )
    }
    entry<ShelfDetail> { args ->
        com.calypsan.listenup.client.features.shelf.ShelfDetailScreen(
            shelfId = args.shelfId,
            onBack = {
                backStack.popFrom(args)
            },
            onBookClick = { bookId ->
                backStack.add(BookDetail(bookId))
            },
            onEditClick = { shelfId ->
                backStack.add(ShelfEdit(shelfId))
            },
        )
    }
    entry<CreateShelf> {
        com.calypsan.listenup.client.features.shelf.CreateEditShelfScreen(
            shelfId = null,
            onBack = {
                backStack.popFrom(CreateShelf)
            },
        )
    }
    entry<ShelfEdit> { args ->
        com.calypsan.listenup.client.features.shelf.CreateEditShelfScreen(
            shelfId = args.shelfId,
            onBack = {
                backStack.popFrom(args)
            },
        )
    }
}
