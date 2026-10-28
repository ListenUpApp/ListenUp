package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import com.calypsan.listenup.client.design.motion.LocalTouchExplorationActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import com.calypsan.listenup.client.presentation.player.MorphEnd

/** The four parts that fly between the bar and Now Playing (canvas `MorphAndroid`). */
internal enum class MorphPart { Cover, Title, Play, Line }

/** The bar's parts that do not fly: they ride the sheet up and fade (canvas `Spec`). */
internal enum class BarDetail { SecondLine, SkipBack }

/** What the morph measures: a flying or riding part at one end, or one of the frames they are measured in. */
internal sealed interface AnchorKey {
    /** One of the four flying [part]s at the morph's [end]. */
    data class Part(
        val end: MorphEnd,
        val part: MorphPart,
    ) : AnchorKey

    /** One of the bar's riding [detail]s. */
    data class Detail(
        val detail: BarDetail,
    ) : AnchorKey

    /** The bar's container, whose top edge is the full travel. */
    data object BarSurface : AnchorKey

    /** The player host: the space the bar's rects and every flying part are in. */
    data object Host : AnchorKey

    /** Now Playing's surface, inside its own transform. */
    data object Sheet : AnchorKey
}

/**
 * Where the morph's two ends are, as live [LayoutCoordinates] read when a frame draws. The bar's parts are measured
 * against the player host; Now Playing's against its own sheet, untransformed, so the sheet's translation never feeds
 * back into its target. The sheet fills the host, so the two spaces agree at rest.
 *
 * Each measurement is owned by the node that made it. Two bars can be on screen together (the shell's leaving as a
 * detail screen's arrives); the one placed last answers while it is attached, and when it goes, its measurements go
 * with it and the other's answer again, so a bar that left never takes the one that stays with it.
 *
 * A plain holder, not snapshot state: measuring never recomposes, and the frames that read it are already redrawing.
 * The one exception is [isReadyState], which changes only when an end appears or goes, so the layers that hide Now
 * Playing's own parts learn the moment both ends are measured.
 */
internal class MorphAnchors {
    private val slots = HashMap<AnchorKey, LinkedHashMap<Any, LayoutCoordinates>>()

    /** The navigation bar's height below the bar on the shell (px); it slides that far down as the sheet rises. */
    var navigationHeightPx: Float = 0f

    /** [owner]'s node, just placed, is [key]: it answers for the key from now on, until it leaves. */
    fun record(
        key: AnchorKey,
        owner: Any,
        coordinates: LayoutCoordinates,
    ) {
        val slot = slots.getOrPut(key) { LinkedHashMap() }
        slot.remove(owner)
        slot[owner] = coordinates
        refreshReadiness()
    }

    /** [owner]'s node has left: whatever it measured goes, and any other node measuring the same keys answers again. */
    fun release(owner: Any) {
        slots.values.forEach { it.remove(owner) }
        refreshReadiness()
    }

    /**
     * [isReady] as snapshot state, for a layer to read: it flips only when an end is first measured or leaves. Without
     * it, a layer that first ran before Now Playing was measured (a drag composes Now Playing mid-gesture) would never
     * run again, and would draw the page's parts under the flying ones for the whole drag.
     */
    var isReadyState: Boolean by mutableStateOf(false)
        private set

    private fun refreshReadiness() {
        val ready = isReady
        if (ready != isReadyState) isReadyState = ready
    }

    /** The node answering for [key]: the last placed of those still attached. */
    fun coordinates(key: AnchorKey): LayoutCoordinates? = slots[key]?.run { values.lastOrNull { it.isAttached } }

    /** The player host. */
    val host: LayoutCoordinates? get() = coordinates(AnchorKey.Host)

    /** Now Playing's sheet. */
    val sheet: LayoutCoordinates? get() = coordinates(AnchorKey.Sheet)

    /** The bar's [part] in host space, or null where the bar does not draw it (a large font drops the cover). */
    fun barRect(part: MorphPart): Rect? = inHost(coordinates(AnchorKey.Part(MorphEnd.Bar, part)))

    /** One of the bar's parts that rides the sheet, in host space. */
    fun barRect(detail: BarDetail): Rect? = inHost(coordinates(AnchorKey.Detail(detail)))

    /** Now Playing's [part] in sheet space, or null where the page does not draw it (a state card has no play). */
    fun pageRect(part: MorphPart): Rect? {
        val sheet = sheet ?: return null
        val node = coordinates(AnchorKey.Part(MorphEnd.NowPlaying, part)) ?: return null
        return sheet.localBoundingBoxOf(node, clipBounds = false)
    }

    /** The bar's container in host space: its top is where the sheet's top edge starts. */
    val barBounds: Rect? get() = inHost(coordinates(AnchorKey.BarSurface))

    /** Whether both ends of the parts every frame needs are measured. The play button is optional: a card replaces it. */
    val isReady: Boolean
        get() = barBounds != null && REQUIRED.all { barRect(it) != null && pageRect(it) != null }

    private fun inHost(coordinates: LayoutCoordinates?): Rect? {
        val host = host ?: return null
        val node = coordinates ?: return null
        return host.localBoundingBoxOf(node, clipBounds = false)
    }

    private companion object {
        val REQUIRED = listOf(MorphPart.Cover, MorphPart.Title, MorphPart.Line)
    }
}

/**
 * Measures this node as [key] on every placement, owned by this node: when the node leaves, the measurement goes with
 * it. Inert without a [LocalPlayerMorph].
 */
@Composable
private fun Modifier.measuresAs(key: AnchorKey): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    val owner = remember { Any() }
    DisposableEffect(morph, owner) { onDispose { morph.anchors.release(owner) } }
    return onGloballyPositioned { morph.anchors.record(key, owner, it) }
}

/**
 * Measures this node as [part] of the morph's [end], and hides it while the part flies in its place. Inert without a
 * [LocalPlayerMorph].
 */
@Composable
internal fun Modifier.morphAnchor(
    end: MorphEnd,
    part: MorphPart,
): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    return measuresAs(AnchorKey.Part(end, part))
        .graphicsLayer { alpha = if (morph.isMorphing && morph.anchors.isReadyState) 0f else 1f }
}

/** Measures this node as one of the bar's riding [detail]s. Inert without a [LocalPlayerMorph]. */
@Composable
internal fun Modifier.morphAnchor(detail: BarDetail): Modifier = measuresAs(AnchorKey.Detail(detail))

/** Measures this node as the bar's container. */
@Composable
internal fun Modifier.morphBarSurface(): Modifier = measuresAs(AnchorKey.BarSurface)

/** Measures this node as the space the morph flies in: the player host. */
@Composable
internal fun Modifier.morphHost(): Modifier = measuresAs(AnchorKey.Host)

/** Measures this node as Now Playing's sheet. Place it inside the sheet's own `graphicsLayer`. */
@Composable
internal fun Modifier.morphSheet(): Modifier = measuresAs(AnchorKey.Sheet)

/** Measures this node as the shell's navigation bar, which slides away as the sheet rises. */
@Composable
internal fun Modifier.morphNavigationBar(): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    return onSizeChanged { morph.anchors.navigationHeightPx = it.height.toFloat() }
}

/** Where focus returns when Now Playing closes from a keyboard or with TalkBack on: the bar's open target. */
@Composable
internal fun Modifier.morphBarFocus(): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    // In touch mode a clickable takes no input focus; with TalkBack on it must, so the close can hand focus back.
    val touchExploration = LocalTouchExplorationActive.current
    return focusRequester(morph.barFocus)
        .then(if (touchExploration) Modifier.focusProperties { canFocus = true } else Modifier)
}

/** Where focus lands when Now Playing opens from a keyboard: Collapse. */
@Composable
internal fun Modifier.morphCollapseFocus(): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    return focusRequester(morph.collapseFocus)
}
