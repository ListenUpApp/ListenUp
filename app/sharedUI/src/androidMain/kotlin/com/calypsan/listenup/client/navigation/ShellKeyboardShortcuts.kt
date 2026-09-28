package com.calypsan.listenup.client.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** A keyboard shortcut the app shell answers. */
internal enum class ShellShortcut {
    /** Space. */
    PlayPause,

    /** Left arrow: back by the user's skip-back interval. */
    SkipBack,

    /** Right arrow: forward by the user's skip-forward interval. */
    SkipForward,

    /** `/` or Ctrl+F. */
    OpenSearch,
}

/**
 * Which [ShellShortcut] a key press is, or null. Only presses count; a held Space does not toggle
 * playback over and over, though a held arrow keeps skipping, as a held seek button would.
 */
internal fun shellShortcutFor(event: KeyEvent): ShellShortcut? {
    if (event.type != KeyEventType.KeyDown || event.isAltPressed || event.isMetaPressed) return null
    if (event.isCtrlPressed) {
        return if (event.key == Key.F && !event.isShiftPressed) ShellShortcut.OpenSearch else null
    }
    if (event.isShiftPressed) return null
    return when (event.key) {
        Key.Spacebar -> ShellShortcut.PlayPause.takeIf { event.nativeKeyEvent.repeatCount == 0 }
        Key.DirectionLeft -> ShellShortcut.SkipBack
        Key.DirectionRight -> ShellShortcut.SkipForward
        Key.Slash -> ShellShortcut.OpenSearch
        else -> null
    }
}

/**
 * The app's keyboard shortcuts, for a tablet or a Chromebook with a hardware keyboard: Space plays
 * or pauses, Left and Right skip, `/` or Ctrl+F opens search. See [shellShortcutFor].
 *
 * The shortcuts belong to the page, not to its controls, so they answer only while focus rests on
 * the shell itself — which is where it sits unless the user has put it somewhere. The shell holds
 * focus as a plain focus target and takes it back whenever it is let go. Once the user focuses a text
 * field, every key is theirs to type; once they tab or arrow to a control, Space activates it and the
 * arrows move on from it, so keyboard focus navigation is untouched. [onShortcut] returns whether it
 * acted; a shortcut it cannot act on (no book loaded, no search here) is not consumed.
 *
 * Off when not [enabled]: a TV's D-pad sends the same arrow and centre keys, and there they must only
 * move focus. The content is then a plain box.
 */
@Composable
internal fun ShellKeyboardShortcuts(
    enabled: Boolean,
    onShortcut: (ShellShortcut) -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!enabled) {
        Box(modifier = modifier, content = content)
        return
    }
    val currentOnShortcut by rememberUpdatedState(onShortcut)
    val shellFocus = remember { FocusRequester() }
    var focusIsOnShell by remember { mutableStateOf(false) }
    var focusIsInShell by remember { mutableStateOf(false) }

    // Take focus at first, and back whenever it leaves the app (a focused control is removed, or
    // focus is cleared to dismiss a keyboard), so the shortcuts never go quiet.
    LaunchedEffect(focusIsInShell) {
        if (!focusIsInShell) shellFocus.requestFocus()
    }

    Box(
        modifier =
            modifier
                .onPreviewKeyEvent { event ->
                    focusIsOnShell && shellShortcutFor(event)?.let(currentOnShortcut) == true
                }.onFocusChanged { state ->
                    focusIsOnShell = state.isFocused
                    focusIsInShell = state.hasFocus
                }.focusRequester(shellFocus)
                .focusTarget(),
        content = content,
    )
}

/**
 * Asks the shell to open search from outside it — the `/` shortcut. One-shot and conflated: a
 * request waits in the buffer until the shell collects [requests], and repeats before then are one.
 */
internal class ShellSearchRequests {
    private val channel = Channel<Unit>(Channel.CONFLATED)

    /** The requests, for the shell to collect. */
    val requests: Flow<Unit> = channel.receiveAsFlow()

    /** Asks for search to open and take focus. */
    fun request() {
        channel.trySend(Unit)
    }
}

/**
 * The `/` shortcut's search requests, for the shell to collect: [ShellSearchRequests.requests] from
 * the app root, like the other app-wide plumbing it provides. Empty outside it.
 */
internal val LocalShellSearchRequests = staticCompositionLocalOf<Flow<Unit>> { emptyFlow() }
