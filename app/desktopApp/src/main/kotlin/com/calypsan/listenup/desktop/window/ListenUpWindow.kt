package com.calypsan.listenup.desktop.window

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import com.calypsan.listenup.client.playback.PlayerSession
import com.calypsan.listenup.client.playback.PlayerSessionState
import com.calypsan.listenup.desktop.DesktopApp
import com.calypsan.listenup.desktop.tray.ListenUpTray
import org.jetbrains.skia.Image
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
        icon = remember { BitmapPainter(loadWindowIcon()) },
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
            val playerSession: PlayerSession = koinInject()

            Surface(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            handleMediaKey(event.key, playerSession)
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
    playerSession: PlayerSession,
): Boolean {
    // Only intercept media keys when a book is loaded. The session's state is eagerly shared, so this
    // reads the truth with no screen collecting it.
    if (playerSession.state.value !is PlayerSessionState.Loaded) return false

    return when (key) {
        Key.MediaPlayPause, Key.Spacebar -> {
            playerSession.playPause()
            true
        }

        Key.MediaNext -> {
            playerSession.skipForward()
            true
        }

        Key.MediaPrevious -> {
            playerSession.skipBack()
            true
        }

        else -> {
            false
        }
    }
}

/** The window icon, decoded from `icon.png` on the classpath (`src/main/resources`). */
private fun loadWindowIcon(): ImageBitmap {
    val stream =
        checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")) {
            "icon.png is missing from the desktop resources"
        }
    return stream.use { Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
}
