package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Composable
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

/**
 * Where the morph's two ends are, as live [LayoutCoordinates] read when a frame draws. The bar's parts are measured
 * against the player host; Now Playing's against its own sheet, untransformed, so the sheet's translation never feeds
 * back into its target. The sheet fills the host, so the two spaces agree at rest.
 *
 * A plain holder, not snapshot state: measuring never recomposes, and the frames that read it are already redrawing.
 */
internal class MorphAnchors {
    private val bar = HashMap<MorphPart, LayoutCoordinates>()
    private val page = HashMap<MorphPart, LayoutCoordinates>()
    private val details = HashMap<BarDetail, LayoutCoordinates>()

    /** The player host: the space the bar's rects and every flying part are in. */
    var host: LayoutCoordinates? = null

    /** Now Playing's surface, inside its own transform. */
    var sheet: LayoutCoordinates? = null

    /** The bar's container, whose top edge is the full travel. */
    var barSurface: LayoutCoordinates? = null

    /** The navigation bar's height below the bar on the shell (px); it slides that far down as the sheet rises. */
    var navigationHeightPx: Float = 0f

    fun record(
        end: MorphEnd,
        part: MorphPart,
        coordinates: LayoutCoordinates,
    ) {
        if (end == MorphEnd.Bar) bar[part] = coordinates else page[part] = coordinates
    }

    fun record(
        detail: BarDetail,
        coordinates: LayoutCoordinates,
    ) {
        details[detail] = coordinates
    }

    /** The bar's [part] in host space, or null where the bar does not draw it (a large font drops the cover). */
    fun barRect(part: MorphPart): Rect? = inHost(bar[part])

    /** One of the bar's parts that rides the sheet, in host space. */
    fun barRect(detail: BarDetail): Rect? = inHost(details[detail])

    /** Now Playing's [part] in sheet space, or null where the page does not draw it (a state card has no play). */
    fun pageRect(part: MorphPart): Rect? {
        val sheet = sheet?.takeIf { it.isAttached } ?: return null
        val node = page[part]?.takeIf { it.isAttached } ?: return null
        return sheet.localBoundingBoxOf(node, clipBounds = false)
    }

    /** The bar's container in host space: its top is where the sheet's top edge starts. */
    val barBounds: Rect? get() = inHost(barSurface)

    /** Whether both ends of the parts every frame needs are measured. The play button is optional: a card replaces it. */
    val isReady: Boolean
        get() = barBounds != null && REQUIRED.all { barRect(it) != null && pageRect(it) != null }

    private fun inHost(coordinates: LayoutCoordinates?): Rect? {
        val host = host?.takeIf { it.isAttached } ?: return null
        val node = coordinates?.takeIf { it.isAttached } ?: return null
        return host.localBoundingBoxOf(node, clipBounds = false)
    }

    private companion object {
        val REQUIRED = listOf(MorphPart.Cover, MorphPart.Title, MorphPart.Line)
    }
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
    return onGloballyPositioned { morph.anchors.record(end, part, it) }
        .graphicsLayer { alpha = if (morph.isMorphing && morph.anchors.isReady) 0f else 1f }
}

/** Measures this node as one of the bar's riding [detail]s. Inert without a [LocalPlayerMorph]. */
@Composable
internal fun Modifier.morphAnchor(detail: BarDetail): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    return onGloballyPositioned { morph.anchors.record(detail, it) }
}

/** Measures this node as the bar's container. */
@Composable
internal fun Modifier.morphBarSurface(): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    return onGloballyPositioned { morph.anchors.barSurface = it }
}

/** Measures this node as the space the morph flies in: the player host. */
@Composable
internal fun Modifier.morphHost(): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    return onGloballyPositioned { morph.anchors.host = it }
}

/** Measures this node as Now Playing's sheet. Place it inside the sheet's own `graphicsLayer`. */
@Composable
internal fun Modifier.morphSheet(): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    return onGloballyPositioned { morph.anchors.sheet = it }
}

/** Measures this node as the shell's navigation bar, which slides away as the sheet rises. */
@Composable
internal fun Modifier.morphNavigationBar(): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    return onSizeChanged { morph.anchors.navigationHeightPx = it.height.toFloat() }
}

/** Where focus returns when Now Playing closes from a keyboard: the bar's open target. */
@Composable
internal fun Modifier.morphBarFocus(): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    return focusRequester(morph.barFocus)
}

/** Where focus lands when Now Playing opens from a keyboard: Collapse. */
@Composable
internal fun Modifier.morphCollapseFocus(): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    return focusRequester(morph.collapseFocus)
}
