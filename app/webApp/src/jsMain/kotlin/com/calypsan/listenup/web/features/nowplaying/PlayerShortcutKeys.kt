package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.shell.KeyPriority
import com.calypsan.listenup.web.shell.WindowKeyHandler
import com.calypsan.listenup.web.shell.keyTargetOf
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * The player's keyboard (spec §5.8), registered with the shell's one key dispatcher at [KeyPriority.Player] for the
 * shell's lifetime, so it works on every page while a book is loaded. [onTogglePlayer] opens Now Playing (or closes it
 * there) and [onClosePlayer] closes it; T, C and ? press the control the reader can see, so its panel opens against it
 * and focus goes back to it, exactly as a click does.
 */
@Composable
fun PlayerShortcutKeys(
    playback: PlaybackSession,
    onPlayingPage: Boolean,
    onTogglePlayer: (opener: HTMLElement?) -> Unit,
    onClosePlayer: () -> Unit,
) {
    WindowKeyHandler(KeyPriority.Player) { event ->
        val loaded = playback.state.value as? PlayerUiState.Loaded
        val context =
            KeyContext(
                isLoaded = loaded != null,
                onPlayingPage = onPlayingPage,
                target = keyTargetOf(event),
                hasPreviousChapter = loaded?.canSkipToPreviousChapter == true,
            )
        val key = playerKeyFor(KeyPress(event), context) ?: return@WindowKeyHandler false
        val done =
            when (key) {
                PlayerKey.Sleep -> pressPlayerControl(PlayerKeyAria.SLEEP)
                PlayerKey.Chapters -> pressPlayerControl(PlayerKeyAria.CHAPTERS)
                PlayerKey.Shortcuts -> pressPlayerControl(PlayerKeyAria.SHORTCUTS)
                PlayerKey.TogglePlayer -> true.also { onTogglePlayer(document.activeElement as? HTMLElement) }
                PlayerKey.ClosePlayer -> true.also { onClosePlayer() }
                else -> true.also { playback.perform(key) }
            }
        if (done) event.preventDefault()
        done
    }
}

/**
 * Presses the control marked with [key] ([DATA_PLAYER_KEY]): the one on screen when there is one, else a hidden one
 * (the bar's, below 760px, whose panel then opens as a sheet). False when the book offers none (no chapters, so no
 * Chapters).
 */
private fun pressPlayerControl(key: String): Boolean {
    val controls =
        document
            .querySelectorAll("[$DATA_PLAYER_KEY='$key']")
            .asList()
            .filterIsInstance<HTMLElement>()
    val control = controls.firstOrNull { it.offsetParent != null } ?: controls.firstOrNull() ?: return false
    control.click()
    return true
}

/** The keys that are a player command and nothing more. */
private fun PlaybackSession.perform(key: PlayerKey) {
    when (key) {
        PlayerKey.PlayPause -> onPlayPause()
        PlayerKey.SkipBack -> onSkipBack()
        PlayerKey.SkipForward -> onSkipForward()
        PlayerKey.PreviousChapter -> onPreviousChapter()
        PlayerKey.NextChapter -> onNextChapter()
        PlayerKey.Slower -> onStepSpeed(false)
        PlayerKey.Faster -> onStepSpeed(true)
        PlayerKey.Sleep, PlayerKey.Chapters, PlayerKey.Shortcuts, PlayerKey.TogglePlayer, PlayerKey.ClosePlayer -> Unit
    }
}
