package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.calypsan.listenup.client.features.settings.HardcoverKeptOffScreen
import com.calypsan.listenup.client.features.settings.HardcoverMatchScreen
import com.calypsan.listenup.client.features.settings.HardcoverSettingsScreen
import com.calypsan.listenup.client.features.settings.NotificationSettingsScreen
import com.calypsan.listenup.client.features.settings.SettingsScreen
import com.calypsan.listenup.client.navigation.popFrom
import com.calypsan.listenup.client.navigation.AdminCategories
import com.calypsan.listenup.client.navigation.Devices
import com.calypsan.listenup.client.navigation.HardcoverKeptOff
import com.calypsan.listenup.client.navigation.HardcoverMatch
import com.calypsan.listenup.client.navigation.HardcoverSettings
import com.calypsan.listenup.client.navigation.LicenseDetail
import com.calypsan.listenup.client.navigation.Licenses
import com.calypsan.listenup.client.navigation.NotificationSettings
import com.calypsan.listenup.client.navigation.Settings
import com.calypsan.listenup.client.navigation.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Settings navigation entries, including the Devices screen. */
internal fun EntryProviderScope<NavKey>.settingsEntries(
    backStack: NavBackStack<NavKey>,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
) {
    entry<Settings> {
        SettingsScreen(
            showDynamicColors = true,
            onNavigateToCategories = { backStack.add(AdminCategories) },
            onNavigateBack = {
                backStack.popFrom(Settings)
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
            onNavigateToNotificationSettings = {
                backStack.add(NotificationSettings)
            },
            onNavigateToHardcover = {
                backStack.add(HardcoverSettings)
            },
        )
    }
    entry<HardcoverSettings> {
        HardcoverSettingsScreen(
            onNavigateBack = {
                backStack.popFrom(HardcoverSettings)
            },
            onFindMatch = { bookId -> backStack.add(HardcoverMatch(bookId)) },
            onOpenKeptOff = { backStack.add(HardcoverKeptOff) },
        )
    }
    entry<HardcoverMatch> { args ->
        HardcoverMatchScreen(
            bookId = args.bookId,
            onNavigateBack = {
                backStack.popFrom(args)
            },
            // The screen is gone once the link lands, so Undo rides the shell's snackbar and scope.
            onLinked = { message, undoLabel, undo ->
                backStack.popFrom(args)
                scope.launch {
                    val result =
                        snackbarHostState.showSnackbar(
                            message,
                            actionLabel = undoLabel,
                            duration = SnackbarDuration.Long,
                        )
                    if (result == SnackbarResult.ActionPerformed) undo()
                }
            },
        )
    }
    entry<HardcoverKeptOff> {
        HardcoverKeptOffScreen(
            onNavigateBack = {
                backStack.popFrom(HardcoverKeptOff)
            },
            // The shell's snackbar: when the last book syncs again this screen closes, and the message still shows.
            onSyncedAgain = { message, close ->
                if (close) backStack.popFrom(HardcoverKeptOff)
                scope.launch { snackbarHostState.showSnackbar(message) }
            },
        )
    }
    entry<NotificationSettings> {
        NotificationSettingsScreen(
            onNavigateBack = {
                backStack.popFrom(NotificationSettings)
            },
        )
    }
    entry<Licenses> {
        com.calypsan.listenup.client.features.settings.LicensesScreen(
            onNavigateBack = { backStack.popFrom(Licenses) },
            onLicenseClick = { uniqueId -> backStack.add(LicenseDetail(uniqueId)) },
        )
    }
    entry<LicenseDetail> { args ->
        com.calypsan.listenup.client.features.settings.LicenseDetailScreen(
            uniqueId = args.uniqueId,
            onNavigateBack = { backStack.popFrom(args) },
        )
    }
    entry<Storage> {
        com.calypsan.listenup.client.features.settings.StorageScreen(
            onNavigateBack = {
                backStack.popFrom(Storage)
            },
        )
    }
    entry<Devices> {
        com.calypsan.listenup.client.features.settings.DevicesScreen(
            onBack = {
                backStack.popFrom(Devices)
            },
        )
    }
}
