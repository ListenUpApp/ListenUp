package com.calypsan.listenup.client.design.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal for app-wide snackbar access.
 * Allows any screen to show snackbars that appear above the mini player.
 */
val LocalSnackbarHostState =
    compositionLocalOf<SnackbarHostState> {
        error("No SnackbarHostState provided")
    }
