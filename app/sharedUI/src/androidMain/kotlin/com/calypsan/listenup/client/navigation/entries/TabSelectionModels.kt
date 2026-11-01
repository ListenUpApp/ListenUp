package com.calypsan.listenup.client.navigation.entries

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * One multi-select model per shell tab. Home, Library and Discover render inside the single Shell navigation entry,
 * so an unkeyed `koinViewModel()` would hand all three the same instance and a selection made on one tab would
 * show on, and act through, the others.
 */
internal class TabSelectionModels(
    val home: BookMultiSelectViewModel,
    val library: BookMultiSelectViewModel,
    val discover: BookMultiSelectViewModel,
) {
    /** Ends the selection made on [destination]'s tab, so leaving a tab never leaves a live selection behind. */
    fun exitSelectionOf(destination: ShellDestination) {
        when (destination) {
            ShellDestination.Home -> home
            ShellDestination.Library -> library
            ShellDestination.Discover -> discover
        }.exitSelectionMode()
    }
}

/** Resolves a [TabSelectionModels] whose three models are distinct instances. */
@Composable
internal fun rememberTabSelectionModels(): TabSelectionModels =
    TabSelectionModels(
        home = koinViewModel(key = "multiSelect:home"),
        library = koinViewModel(key = "multiSelect:library"),
        discover = koinViewModel(key = "multiSelect:discover"),
    )
