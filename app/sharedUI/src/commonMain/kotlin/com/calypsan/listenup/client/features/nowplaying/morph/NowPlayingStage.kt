package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.calypsan.listenup.client.features.nowplaying.EntryMotion
import com.calypsan.listenup.client.features.nowplaying.NowPlayingLayout
import com.calypsan.listenup.client.features.nowplaying.nowPlayingEntryMotion
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState

/** The fade-through's starting (and ending) scale: M3's 92–96 % (canvas `Edges`, "wide windows"). */
private const val FADE_THROUGH_SCALE = 0.96f

/** Where the mini player sits under Now Playing: the shell's bar, a detail screen's, or none over the reader. */
internal enum class BarHome { Shell, Detail, Reader }

/**
 * How Now Playing arrives and leaves here, now: [nowPlayingEntryMotion] with the destination change worked out. A close
 * fades through when the bar's home is no longer the one Now Playing opened over (Go to series from the shell lands on
 * a detail screen whose bar sits lower), and so does an open that comes straight back from the reader, over which no
 * bar was showing.
 *
 * @param isPresented Whether Now Playing is on screen or still leaving, so the decision holds through the exit.
 */
@Composable
internal fun rememberEntryMotion(
    usesPhoneBar: Boolean,
    layout: NowPlayingLayout,
    home: BarHome,
    isExpanded: Boolean,
    isPresented: Boolean,
): EntryMotion {
    // Decided while composing, the frame the open or the move happens: a plain record, never state, so deciding
    // recomposes nothing.
    val record = remember { OpenRecord(home) }
    var leftReaderTick by remember { mutableIntStateOf(0) }
    if (record.lastHome != home) {
        if (record.lastHome == BarHome.Reader) {
            record.leftReader = true
            leftReaderTick++
        }
        record.lastHome = home
    }
    if (isExpanded && !record.wasExpanded) {
        record.homeAtOpen = if (record.leftReader) BarHome.Reader else home
        record.leftReader = false
    }
    record.wasExpanded = isExpanded
    LaunchedEffect(leftReaderTick) {
        // The reader's re-expand lands within a frame or two of the reader closing; after that an open is the bar's.
        withFrameNanos {}
        withFrameNanos {}
        record.leftReader = false
    }
    val opened = record.homeAtOpen
    val changed = (isExpanded || isPresented) && opened != null && opened != home
    return nowPlayingEntryMotion(usesPhoneBar = usesPhoneBar, layout = layout, destinationChanged = changed)
}

/** What [rememberEntryMotion] remembers between compositions. */
private class OpenRecord(
    var lastHome: BarHome,
) {
    var homeAtOpen: BarHome? = null
    var wasExpanded: Boolean = false
    var leftReader: Boolean = false
}

/**
 * Now Playing over the mini player, the way the player host stacks it: the scrim, Now Playing itself while it is
 * expanded or the morph is off the bar, and the flying parts above both. Keeps [morph] moving toward [isExpanded].
 * Along the morph Now Playing has no enter or exit of its own (the morph is its motion); otherwise it fades through,
 * fading in while it scales from 96 % on M3 Expressive's effects and spatial springs.
 */
@Composable
internal fun BoxScope.NowPlayingStage(
    morph: PlayerMorphState?,
    entryMotion: EntryMotion,
    isExpanded: Boolean,
    loaded: PlayerUiState.Loaded?,
    progress: () -> PlayerProgress,
    skipBackwardSec: Int,
    visibility: MutableTransitionState<Boolean>,
    player: @Composable (PlayerUiState.Loaded) -> Unit,
) {
    if (morph != null) PlayerMorphSync(morph = morph, isExpanded = isExpanded, entryMotion = entryMotion)
    val morphs = morph != null && entryMotion == EntryMotion.Morph
    val morphShowsPlayer = morph != null && rememberMorphShowsPlayer(morph)
    val motion = MaterialTheme.motionScheme
    if (morph != null && morphs && loaded != null) PlayerMorphScrim(morph)
    visibility.targetState = loaded != null && (isExpanded || (morphs && morphShowsPlayer))
    AnimatedVisibility(
        visibleState = visibility,
        enter =
            if (morphs) {
                EnterTransition.None
            } else {
                fadeIn(motion.defaultEffectsSpec()) +
                    scaleIn(initialScale = FADE_THROUGH_SCALE, animationSpec = motion.defaultSpatialSpec())
            },
        exit =
            if (morphs) {
                ExitTransition.None
            } else {
                fadeOut(motion.fastEffectsSpec()) +
                    scaleOut(targetScale = FADE_THROUGH_SCALE, animationSpec = motion.defaultSpatialSpec())
            },
    ) {
        if (loaded != null) player(loaded)
    }
    if (morph != null && loaded != null) {
        PlayerMorphFlight(morph = morph, state = loaded, progress = progress, skipBackwardSec = skipBackwardSec)
    }
}
