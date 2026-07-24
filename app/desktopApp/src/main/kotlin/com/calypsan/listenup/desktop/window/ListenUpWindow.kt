package com.calypsan.listenup.desktop.window

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.presentation.nowplaying.NowPlayingViewModel
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.desktop.DesktopApp
import com.calypsan.listenup.desktop.tray.ListenUpTray
import org.koin.compose.koinInject

/**
 * Main window for the ListenUp desktop application.
 *
 * Features:
 * - Material 3 theming with ListenUp brand colors
 * - System tray integration for background playback
 * - Minimize to tray support (when playing)
 */
@Composable
fun ListenUpWindow(
    state: WindowState,
    onCloseRequest: () -> Unit,
) {
    var isVisible by remember { mutableStateOf(true) }

    // System tray for background playback
    ListenUpTray(
        onOpenWindow = { isVisible = true },
        onExitApplication = onCloseRequest,
    )

    Window(
        onCloseRequest = {
            // TODO: Minimize to tray instead of closing if playing
            // For now, just close
            onCloseRequest()
        },
        visible = isVisible,
        title = "ListenUp",
        state = state,
        icon =
            androidx.compose.ui.res
                .painterResource("icon.png"),
    ) {
        val localPreferences: LocalPreferences = koinInject()
        val themeMode by localPreferences.themeMode.collectAsStateWithLifecycle()
        val isDark =
            when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

        ListenUpTheme(
            darkTheme = isDark,
        ) {
            val nowPlayingViewModel: NowPlayingViewModel = koinInject()

            Surface(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            handleMediaKey(event.key, nowPlayingViewModel)
                        },
            ) {
                DesktopApp()
            }
        }
    }
}

/**
 * Handle media keys and playback shortcuts when the window has focus.
 *
 * Supports standard media keys and Space as play/pause toggle.
 * Returns true if the key was consumed.
 */
private fun handleMediaKey(
    key: Key,
    nowPlayingViewModel: NowPlayingViewModel,
): Boolean {
    // Only intercept media keys when there's a book loaded (any non-Idle variant).
    if (nowPlayingViewModel.screenState.value.state is NowPlayingState.Idle) return false

    return when (key) {
        Key.MediaPlayPause, Key.Spacebar -> {
            nowPlayingViewModel.playPause()
            true
        }

        Key.MediaNext -> {
            nowPlayingViewModel.skipForward()
            true
        }

        Key.MediaPrevious -> {
            nowPlayingViewModel.skipBack()
            true
        }

        else -> {
            false
        }
    }
}
