package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composition
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.presentation.player.PlayerOverlay
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.mountAt
import com.calypsan.listenup.web.motions
import com.calypsan.listenup.web.nav.Router
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.w3c.dom.DOMRect
import org.w3c.dom.HTMLElement

/** The whole shell, at [width], at [path], with [playback] behind its bar: what every morph spec drives. */
internal class MorphRig(
    val frame: ViewportFrame,
    val router: Router,
    val composition: Composition,
) {
    val shell: HTMLElement get() = frame.find(".shell")

    /** The progress CSS is reading right now, animation included. */
    fun progress(): Double = frame.css(shell, MORPH_PROGRESS).trim().toDouble()

    /** The one animation driving progress, if one is running. */
    fun morphAnimation(): dynamic = shell.motions().singleOrNull()

    fun hasMorphAnimation(): Boolean = shell.motions().isNotEmpty()

    fun rect(selector: String): DOMRect = frame.rect(frame.find(selector))

    fun isMorphing(): Boolean = shell.classList.contains("shell-morphing")

    /** What the page region draws besides Now Playing: the page the sheet rose from, or is closing onto. */
    fun underPage(): List<HTMLElement> = frame.findAll(".page-body > :not(.np-slot)")

    fun dispose() {
        composition.dispose()
        router.dispose()
    }
}

internal fun ViewportFrames.morphRig(
    path: String,
    width: Int = MORPH_PHONE_WIDTH,
    height: Int = MORPH_PHONE_HEIGHT,
    playback: OpenPlayback = fixedPlayback(state = loadedPlayerState()),
    compositionProbe: (String) -> Unit = {},
): MorphRig {
    val frame = host(width, height)
    // Measured rects (the cover the morph flies from) must not shift as the web fonts arrive: pin the fallback faces.
    frame.withoutWebFonts()
    val (_, router, composition) =
        mountAt(path, openPlayback = playback, into = frame, compositionProbe = compositionProbe)
    return MorphRig(frame, router, composition)
}

/** A session whose state the spec can change mid-morph (a book switch), otherwise as fixed as [fixedPlayback]'s. */
internal fun switchablePlayback(state: MutableStateFlow<PlayerUiState>): OpenPlayback =
    {
        PlaybackSession(
            state = state,
            progress = MutableStateFlow(PlayerProgress.Zero),
            loadedBook =
                state.map { current ->
                    (current as? PlayerUiState.Loaded)?.let {
                        LoadedBook.Loaded(
                            it.book.id,
                            isPlaying = true,
                            isFinished = false,
                        )
                    }
                        ?: LoadedBook.NothingLoaded
                },
            overlay = MutableStateFlow(PlayerOverlay.None),
            preparingBookId = MutableStateFlow(null),
            boostUnavailable = MutableStateFlow(false),
            events = emptyFlow(),
            onPrimary = {},
            onPlayPause = {},
            onPlayBook = { _, _ -> },
            onSeek = {},
            onSkipBack = {},
            onSkipForward = {},
            onPreviousChapter = {},
            onNextChapter = {},
            onSeekToChapter = {},
            onSetSpeed = {},
            onResetSpeed = {},
            onStepSpeed = {},
            onSetBoost = {},
            onResetBoost = {},
            onSetSleepTimer = {},
            onCancelSleepTimer = {},
            onExtendSleepTimer = {},
            onRetry = {},
            onDismissError = {},
            onCloseBook = {},
            onCardAction = {},
            onRate = {},
            close = {},
        )
    }

internal const val MORPH_PHONE_WIDTH = 390

internal const val MORPH_PHONE_HEIGHT = 844
