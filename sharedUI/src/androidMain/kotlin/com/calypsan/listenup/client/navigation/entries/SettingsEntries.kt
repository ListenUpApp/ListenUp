package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.features.settings.SettingsScreen
import com.calypsan.listenup.client.navigation.Devices
import com.calypsan.listenup.client.navigation.Licenses
import com.calypsan.listenup.client.navigation.Settings
import com.calypsan.listenup.client.navigation.Storage

/**
 * Settings navigation entries.
 *
 * Note: [Devices] is intentionally excluded — it captures [scope], [libraryResetHelper], and
 * [authSession] from [AuthenticatedNavigation] and must remain inline.
 */
internal fun EntryProviderScope<NavKey>.settingsEntries(backStack: NavBackStack<NavKey>) {
    entry<Settings> {
        SettingsScreen(
            showDynamicColors = true,
            onNavigateBack = {
                backStack.removeAt(backStack.lastIndex)
            },
            onNavigateToDevices = {
                backStack.add(Devices)
            },
            onNavigateToStorage = {
                backStack.add(Storage)
            },
            onNavigateToLicenses = {
                backStack.add(Licenses)
            },
        )
    }
    entry<Licenses> {
        com.calypsan.listenup.client.features.settings.LicensesScreen(
            onNavigateBack = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    entry<Storage> {
        com.calypsan.listenup.client.features.settings.StorageScreen(
            onNavigateBack = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
}
