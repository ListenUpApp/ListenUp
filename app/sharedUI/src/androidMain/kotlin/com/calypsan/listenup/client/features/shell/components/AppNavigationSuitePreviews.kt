package com.calypsan.listenup.client.features.shell.components

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.features.shell.ShellNavType

/**
 * Isolated previews of the shell navigation surface — the rail (expanded/Desktop face) and the
 * compact bottom bar — against the static fallback palette so the designed coral selected-indicator
 * renders. `AppNavigationSuite` has no DI, so it previews directly with plain state.
 */
@Composable
private fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ListenUpTheme(darkTheme = dark, dynamicColor = false, content = content)
}

@Preview(name = "Rail · light", widthDp = 100, heightDp = 640)
@Composable
private fun RailLight() {
    PreviewTheme(dark = false) {
        AppNavigationSuite(
            navType = ShellNavType.RailExpanded,
            currentDestination = ShellDestination.Library,
            onDestinationSelected = {},
            onSignOut = {},
            modifier = Modifier.fillMaxHeight(),
        )
    }
}

@Preview(name = "Rail · dark", widthDp = 100, heightDp = 640)
@Composable
private fun RailDark() {
    PreviewTheme(dark = true) {
        AppNavigationSuite(
            navType = ShellNavType.RailExpanded,
            currentDestination = ShellDestination.Library,
            onDestinationSelected = {},
            onSignOut = {},
            modifier = Modifier.fillMaxHeight(),
        )
    }
}

@Preview(name = "Bottom bar · light", widthDp = 412)
@Composable
private fun BottomBarLight() {
    PreviewTheme(dark = false) {
        AppNavigationSuite(
            navType = ShellNavType.BottomBar,
            currentDestination = ShellDestination.Home,
            onDestinationSelected = {},
            onSignOut = {},
        )
    }
}

@Preview(name = "Bottom bar · dark", widthDp = 412)
@Composable
private fun BottomBarDark() {
    PreviewTheme(dark = true) {
        AppNavigationSuite(
            navType = ShellNavType.BottomBar,
            currentDestination = ShellDestination.Home,
            onDestinationSelected = {},
            onSignOut = {},
        )
    }
}
