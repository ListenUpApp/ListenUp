package com.calypsan.listenup.client.features.nowplaying.morph

import com.calypsan.listenup.client.presentation.player.settleTarget
import com.calypsan.listenup.client.presentation.player.PlayerViewModel
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import com.calypsan.listenup.client.design.motion.LocalReduceMotion
import com.calypsan.listenup.client.design.motion.LocalTouchExplorationActive
import com.calypsan.listenup.client.features.nowplaying.EntryMotion
import com.calypsan.listenup.client.presentation.player.BackPreviewWeight
import com.calypsan.listenup.client.presentation.player.MorphEnd
import com.calypsan.listenup.client.presentation.player.MorphMode
import com.calypsan.listenup.client.presentation.player.PlayerMorph
import com.calypsan.listenup.client.presentation.player.morphMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The one progress Now Playing's open and close move along: 0 is the bar, 1 is Now Playing. It lives at the player
 * host, and the shell reads it through [LocalPlayerMorph], so the bar, the navigation bar and the sheet move as one.
 *
 * Read [progress] only inside `graphicsLayer {}`, draw and `layout {}` lambdas, never while composing: a frame then
 * redraws, and nothing recomposes. The shared [PlayerSheetState] stays the destination; this animates toward it, a drag
 * owns it until release, and a settle writes the destination back once, only when it differs.
 */
@Stable
internal class PlayerMorphState(
    private val scope: CoroutineScope,
    private val isExpanded: () -> Boolean,
    private val onExpand: () -> Unit,
    private val onCollapse: () -> Unit,
) {
    /** Where the morph is, 0 (the bar) to 1 (Now Playing); a spring may carry it a little past 1. */
    var progress: Float by mutableFloatStateOf(0f)
        private set

    /** True from a drag's first move or a settle's start until it comes to rest at an end. */
    var isInFlight: Boolean by mutableStateOf(false)
        private set

    /** How Now Playing arrives in the current window and destination; set by the host. */
    var entryMotion: EntryMotion by mutableStateOf(EntryMotion.Morph)

    /** The morph, or the crossfade when animations are removed. */
    var mode: MorphMode by mutableStateOf(MorphMode.Morph)

    /** Material's back preview carried into a committed close, weighted away as it lands. */
    var backPreview: BackPreviewWeight by mutableStateOf(BackPreviewWeight.None)

    /** Where both ends were last measured. A plain holder: measuring never recomposes. */
    val anchors: MorphAnchors = MorphAnchors()

    /** The bar's open target, which takes focus back after a keyboard close. */
    val barFocus: FocusRequester = FocusRequester()

    /** Now Playing's Collapse, which takes focus after a keyboard open. */
    val collapseFocus: FocusRequester = FocusRequester()

    /** Counts the settles that came to rest, so focus can follow each one ([MorphFocusEffect]). */
    var settles: Int by mutableIntStateOf(0)
        private set

    /** Where the last settle came to rest. */
    var lastSettle: MorphEnd = MorphEnd.Bar
        private set

    /** Whether a finger let the last settle go. */
    var lastSettleWasDrag: Boolean = false
        private set

    /** True while a finger holds the progress. */
    var isDragging: Boolean = false
        private set

    /** Whether the parts are flying: the morph (not the crossfade, not a fade-through) is between its ends. */
    val isMorphing: Boolean
        get() = isInFlight && entryMotion == EntryMotion.Morph && mode == MorphMode.Morph

    internal var settleSpec: AnimationSpec<Float> =
        spring(PlayerMorph.SETTLE_SPRING.dampingRatio, PlayerMorph.SETTLE_SPRING.stiffness)
    internal var tapSpec: AnimationSpec<Float> =
        spring(PlayerMorph.TAP_SPRING.dampingRatio, PlayerMorph.TAP_SPRING.stiffness)
    private var settling: Job? = null

    /** A finger moved the morph to [p]: it owns the progress from now until [release]. */
    fun snapDrag(p: Float) {
        if (!isDragging) {
            settling?.cancel()
            isDragging = true
        }
        isInFlight = true
        progress = p.coerceIn(0f, 1f)
    }

    /**
     * The finger let go: settle at [end] on the default spatial spring, carrying [velocity] (progress per second), or
     * snap there under the crossfade.
     */
    fun release(
        end: MorphEnd,
        velocity: Float,
    ): Job {
        isDragging = false
        return settle(
            end = end,
            velocity = velocity,
            spec = if (mode == MorphMode.Crossfade) null else settleSpec,
            byDrag = true,
        )
    }

    /**
     * The surface a finger was dragging has gone (the bar unloaded, the window changed under it): let go where it is,
     * settling by position as a release with no velocity would. Nothing when no drag is held.
     */
    fun cancelDrag() {
        if (!isDragging) return
        release(settleTarget(progress, velocityDpPerSec = 0f), velocity = 0f)
    }

    /** Settle at [end] as a tap or Collapse does: the slow spatial spring opens, the default spatial spring closes. */
    fun animateTo(end: MorphEnd): Job {
        val spec =
            when {
                mode == MorphMode.Crossfade -> null
                end == MorphEnd.NowPlaying -> tapSpec
                else -> settleSpec
            }
        return settle(end = end, velocity = 0f, spec = spec, byDrag = false)
    }

    /** Jump to [end] with no motion (a fade-through carries the change itself). */
    fun snapTo(end: MorphEnd) {
        settling?.cancel()
        isDragging = false
        progress = end.progress
        isInFlight = false
        backPreview = BackPreviewWeight.None
    }

    private fun settle(
        end: MorphEnd,
        velocity: Float,
        spec: AnimationSpec<Float>?,
        byDrag: Boolean,
    ): Job {
        settling?.cancel()
        isInFlight = true
        val job =
            scope.launch {
                if (spec != null) {
                    animate(
                        initialValue = progress,
                        targetValue = end.progress,
                        initialVelocity = velocity,
                        animationSpec = spec,
                    ) { value, _ -> progress = value }
                }
                progress = end.progress
                isInFlight = false
                backPreview = BackPreviewWeight.None
                writeDestination(end)
                lastSettle = end
                lastSettleWasDrag = byDrag
                settles++
            }
        settling = job
        return job
    }

    private fun writeDestination(end: MorphEnd) {
        val open = end == MorphEnd.NowPlaying
        if (open == isExpanded()) return
        if (open) onExpand() else onCollapse()
    }
}

private val MorphEnd.progress: Float get() = if (this == MorphEnd.NowPlaying) 1f else 0f

/**
 * The player host's morph, for the shell to read. Null where nothing hosts the player (desktop, previews): every
 * reader then draws the bar at rest and the player closed.
 */
internal val LocalPlayerMorph = staticCompositionLocalOf<PlayerMorphState?> { null }

/** A [PlayerMorphState] whose settles write back through [onExpand] and [onCollapse] when [isExpanded] differs. */
@Composable
internal fun rememberPlayerMorph(
    isExpanded: () -> Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
): PlayerMorphState {
    val scope = rememberCoroutineScope()
    val expanded by rememberUpdatedState(isExpanded)
    val expand by rememberUpdatedState(onExpand)
    val collapse by rememberUpdatedState(onCollapse)
    return remember(scope) {
        PlayerMorphState(
            scope = scope,
            isExpanded = { expanded() },
            onExpand = { expand() },
            onCollapse = { collapse() },
        )
    }
}

/** The player's [PlayerMorphState], writing back to [viewModel]'s shared destination. */
@Composable
internal fun rememberPlayerMorph(viewModel: PlayerViewModel): PlayerMorphState =
    rememberPlayerMorph(
        isExpanded = { viewModel.expanded.value },
        onExpand = viewModel::expand,
        onCollapse = viewModel::collapse,
    )

/**
 * Keeps [morph] moving toward the destination [isExpanded]: a change from anywhere (the bar's tap, Collapse, a
 * shortcut, the reader's return) plays as a tap or Collapse does, unless a finger holds the progress. Under a
 * fade-through the progress simply follows, since the fade carries the change.
 */
@Composable
internal fun PlayerMorphSync(
    morph: PlayerMorphState,
    isExpanded: Boolean,
    entryMotion: EntryMotion,
) {
    val reduceMotion = LocalReduceMotion.current
    val motion = MaterialTheme.motionScheme
    val settleSpec = motion.defaultSpatialSpec<Float>()
    val tapSpec = motion.slowSpatialSpec<Float>()
    SideEffect {
        morph.entryMotion = entryMotion
        morph.mode = morphMode(reduceMotion)
        morph.settleSpec = settleSpec
        morph.tapSpec = tapSpec
    }
    LaunchedEffect(morph, isExpanded, entryMotion) {
        val end = if (isExpanded) MorphEnd.NowPlaying else MorphEnd.Bar
        // Away from the morph's geometry (a wider window, a fold), progress follows the destination at once, a held
        // drag included: the surface it was dragging may be gone, and nothing may be left half risen.
        if (entryMotion != EntryMotion.Morph) {
            morph.snapTo(end)
            return@LaunchedEffect
        }
        if (morph.isDragging) return@LaunchedEffect
        if (!morph.isInFlight && morph.progress == end.progress) return@LaunchedEffect
        morph.animateTo(end)
    }
}

/**
 * Focus follows a keyboard: an open that came to rest gives it to Collapse, a close gives it back to the bar's open
 * target (canvas `Edges`, never stranded). With TalkBack exploring by touch, a close gives it back to the bar too, so
 * the screen reader is not left on whatever the system picks. A drag in touch mode moves no input focus.
 */
@Composable
internal fun MorphFocusEffect(morph: PlayerMorphState) {
    val inputMode = LocalInputModeManager.current
    val touchExploration = LocalTouchExplorationActive.current
    val settles = morph.settles
    LaunchedEffect(morph, settles) {
        if (settles == 0 || morph.lastSettleWasDrag) return@LaunchedEffect
        val opened = morph.lastSettle == MorphEnd.NowPlaying
        val keyboard = inputMode.inputMode == InputMode.Keyboard
        if (!keyboard && !(touchExploration && !opened)) return@LaunchedEffect
        // A frame for the destination's recomposition: the bar takes focus only once Now Playing has left it.
        withFrameNanos {}
        withFrameNanos {}
        (if (opened) morph.collapseFocus else morph.barFocus).requestFocus()
    }
}
