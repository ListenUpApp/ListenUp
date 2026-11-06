package com.calypsan.listenup.client.navigation

import android.view.KeyEvent
import android.view.KeyboardShortcutInfo
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.shell_shortcut_play_pause
import listenup.composeapp.generated.resources.shell_shortcut_search
import listenup.composeapp.generated.resources.shell_shortcut_skip_back
import listenup.composeapp.generated.resources.shell_shortcut_skip_forward
import org.jetbrains.compose.resources.getString

/** The names the system's shortcut helper shows next to each [ShellShortcut]'s keys. */
internal data class ShellShortcutLabels(
    val playPause: String,
    val skipBack: String,
    val skipForward: String,
    val search: String,
)

/**
 * Resolves [ShellShortcutLabels] from the string catalog in the current locale.
 *
 * Suspends, because `Activity.onProvideKeyboardShortcuts` is a synchronous callback: the activity
 * loads this once and the callback reads the snapshot, the same way `SystemStrings` serves Media3.
 */
internal suspend fun loadShellShortcutLabels(): ShellShortcutLabels =
    ShellShortcutLabels(
        playPause = getString(Res.string.shell_shortcut_play_pause),
        skipBack = getString(Res.string.shell_shortcut_skip_back),
        skipForward = getString(Res.string.shell_shortcut_skip_forward),
        search = getString(Res.string.shell_shortcut_search),
    )

/**
 * The keys [shellShortcutFor] answers, as the system's keyboard-shortcuts helper (Meta+/) lists them.
 * Keep this in step with [ShellShortcut]: one entry per key, and search appears twice (`/` and Ctrl+F).
 */
internal fun shellShortcutHelp(labels: ShellShortcutLabels): List<KeyboardShortcutInfo> =
    listOf(
        KeyboardShortcutInfo(labels.playPause, KeyEvent.KEYCODE_SPACE, 0),
        KeyboardShortcutInfo(labels.skipBack, KeyEvent.KEYCODE_DPAD_LEFT, 0),
        KeyboardShortcutInfo(labels.skipForward, KeyEvent.KEYCODE_DPAD_RIGHT, 0),
        KeyboardShortcutInfo(labels.search, KeyEvent.KEYCODE_SLASH, 0),
        KeyboardShortcutInfo(labels.search, KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON),
    )
