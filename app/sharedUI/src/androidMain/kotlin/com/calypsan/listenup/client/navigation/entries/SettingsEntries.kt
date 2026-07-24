package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.features.settings.SettingsScreen
import com.calypsan.listenup.client.navigation.Devices
import com.calypsan.listenup.client.navigation.LicenseDetail
import com.calypsan.listenup.client.navigation.Licenses
import com.calypsan.listenup.client.navigation.Settings
import com.calypsan.listenup.client.navigation.Storage

/** Settings navigation entries, including the Devices screen. */
internal fun EntryProviderScope<NavKey>.settingsEntries(
    backStack: NavBackStack<NavKey>,
    onSignOut: () -> Unit,
) {
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
            onNavigateBack = { backStack.removeAt(backStack.lastIndex) },
            onLicenseClick = { backStack.add(LicenseDetail(it)) },
        )
    }
    entry<LicenseDetail> { args ->
        com.calypsan.listenup.client.features.settings.LicenseDetailScreen(
            uniqueId = args.uniqueId,
            onNavigateBack = { backStack.removeAt(backStack.lastIndex) },
        )
    }
    entry<Storage> {
        com.calypsan.listenup.client.features.settings.StorageScreen(
            onNavigateBack = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    entry<Devices> {
        com.calypsan.listenup.client.features.settings.DevicesScreen(
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
            onSignedOutEverywhere = {
                // Same local teardown as the Shell sign-out action (LogoutUseCase, via
                // onSignOut) — the server-side revoke-all already happened in
                // signOutEverywhere(); this just clears local state so auth-state
                // routing returns the user to login.
                onSignOut()
            },
        )
    }
}
