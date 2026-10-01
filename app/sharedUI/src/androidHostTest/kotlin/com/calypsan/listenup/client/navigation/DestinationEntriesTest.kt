package com.calypsan.listenup.client.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import com.calypsan.listenup.client.features.bulkedit.PendingSelectionExit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Every [Route] resolves to a screen.
 *
 * Nav3's `entryProvider` answers a key it has no `entry<>` for by throwing "Unknown screen" — on a
 * device, the moment the route is pushed. Nothing else would notice a deleted or never-written
 * entry: the route still compiles, still serializes, and every screen's own spec still passes.
 */
class DestinationEntriesTest :
    FunSpec({
        test("every route above the shell resolves to an entry") {
            val unresolved = mutableListOf<NavKey>()
            val resolve =
                entryProvider<NavKey>(
                    fallback = { key ->
                        unresolved += key
                        NavEntry(key) {}
                    },
                ) {
                    destinationEntries(
                        backStack = NavBackStack(),
                        scope = CoroutineScope(Dispatchers.Unconfined),
                        snackbarHostState = SnackbarHostState(),
                        pendingSelectionExit = PendingSelectionExit(),
                        profileRefreshKey = 0,
                        onProfileRefreshed = {},
                        onNotificationAction = {},
                    )
                }

            sampleRoutes().forEach { resolve(it) }

            unresolved.map { it::class }.toSet() shouldBe
                setOf(
                    // Registered beside the shell itself, where their live ViewModels are.
                    Shell::class,
                    LibrarySetup::class,
                )
        }
    })
