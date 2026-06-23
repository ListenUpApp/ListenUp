package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Drop-in replacement for [androidx.compose.material3.Scaffold] for any screen rendered over
 * the floating now-playing bar. It reserves the player's footprint (via [LocalNowPlayingInsets])
 * **and** the system navigation bar in a single bottom spacer, so:
 *
 * - scrolling content set up with the supplied `paddingValues` never hides behind the bar,
 * - a [bottomBar] (e.g. a docked Save action) sits directly above the bar and glides with it,
 * - a [floatingActionButton] clears the bar automatically (Scaffold lifts it above `bottomBar`).
 *
 * The bottom is owned entirely by the spacer; [androidx.compose.material3.Scaffold]'s
 * `contentWindowInsets` is restricted to top + horizontal so nothing is double-counted.
 */
@Composable
fun ListenUpScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    snackbarHost: @Composable () -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable (PaddingValues) -> Unit,
) {
    // max-per-side: when the player is active its footprint already includes the nav bar, so it
    // wins; when idle the player inset is zero and the system nav bar applies.
    val bottomClearance = WindowInsets.systemBars.union(LocalNowPlayingInsets.current)

    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = {
            Column {
                bottomBar()
                Spacer(Modifier.windowInsetsBottomHeight(bottomClearance))
            }
        },
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        snackbarHost = snackbarHost,
        containerColor = containerColor,
        contentWindowInsets = WindowInsets.systemBars
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        content = content,
    )
}
