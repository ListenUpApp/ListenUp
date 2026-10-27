package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.nowplaying.EntryMotion
import com.calypsan.listenup.client.presentation.player.MorphElement
import com.calypsan.listenup.client.presentation.player.MorphMode
import com.calypsan.listenup.client.presentation.player.MorphRect
import com.calypsan.listenup.client.presentation.player.reveal
import com.calypsan.listenup.client.presentation.player.sheetCornerFraction
import com.calypsan.listenup.client.presentation.player.sidesScale

/** The scrim's darkest, over Home, at Now Playing (canvas `Spec`: 0 → 32 % black). */
private const val SCRIM_ALPHA = 0.32f

/** The sheet's top corner radius at the middle of the morph (`28·sin(πp)`). */
internal val SheetMorphCorner = 28.dp

/**
 * Whether [morph] is moving between the bar and Now Playing in a way the sheet shows: a morph or the crossfade, never a
 * fade-through. Read only inside layer and draw lambdas.
 */
internal val PlayerMorphState.drivesSheet: Boolean
    get() = entryMotion == EntryMotion.Morph && (isInFlight || progress < 1f)

/** The sheet's top edge at progress [p] (host px): from the bar's top to the window's, clamped at 1 so overshoot never opens a gap. */
internal fun PlayerMorphState.sheetTop(p: Float): Float {
    val barTop = anchors.barBounds?.top ?: return 0f
    return (1f - p.coerceIn(0f, 1f)) * barTop
}

/** The shell's navigation bar's slide down at progress [p] (px): its own height across 0–0.4, ease-out cubic. */
internal fun PlayerMorphState.navigationSlide(p: Float): Float =
    anchors.navigationHeightPx * reveal(MorphElement.NavigationBar, p)

/**
 * The bottom of what the sheet may cover at progress [p] (host px): the bar's bottom plus the navigation bar's share
 * that has slid away, so the navigation bar stays drawn as it leaves (canvas `MorphAndroid`). Null for no limit.
 */
internal fun PlayerMorphState.coverBottom(p: Float): Float? {
    val bar = anchors.barBounds ?: return null
    return bar.bottom + navigationSlide(p)
}

/**
 * The scrim over Home: 0 → 32 % black in progress, never over the navigation bar while it leaves. Drawn under Now
 * Playing; nothing while the morph is at rest closed.
 */
@Composable
internal fun BoxScope.PlayerMorphScrim(morph: PlayerMorphState) {
    Spacer(
        Modifier
            .matchParentSize()
            .graphicsLayer { scrimLayer(morph) }
            .background(Color.Black),
    )
}

/** The scrim's layer at the current progress. */
internal fun GraphicsLayerScope.scrimLayer(morph: PlayerMorphState) {
    val p = morph.progress
    alpha = if (morph.entryMotion == EntryMotion.Morph) SCRIM_ALPHA * p.coerceIn(0f, 1f) else 0f
    val bottom = morph.coverBottom(p)
    if (bottom != null && bottom < size.height) {
        shape = bottomClip(bottom)
        clip = true
    }
}

/** Clips to everything above [bottom]. */
private fun bottomClip(bottom: Float): Shape =
    GenericShape { size, _ -> addRect(Rect(left = 0f, top = 0f, right = size.width, bottom = bottom)) }

/**
 * Now Playing's sheet, along the morph: its top edge rises from the bar's, its top corners round and flatten, its
 * colour runs from the bar's [barColour] to [surfaceColour], and below it the navigation bar stays visible as it
 * slides. Under the crossfade it sits at rest and only its opacity follows progress. Everything is read in layer and
 * draw lambdas, so a frame recomposes nothing.
 */
internal fun Modifier.morphSheetLayer(
    morph: PlayerMorphState?,
    barColour: Color,
    surfaceColour: Color,
): Modifier {
    if (morph == null) return background(surfaceColour)
    return graphicsLayer { sheetLayer(morph) }.drawBehind {
        val colour =
            if (morph.drivesSheet && morph.mode == MorphMode.Morph) {
                lerp(barColour, surfaceColour, reveal(MorphElement.SheetColour, morph.progress))
            } else {
                surfaceColour
            }
        drawRect(colour)
    }
}

/** The sheet's layer at the current progress. */
internal fun GraphicsLayerScope.sheetLayer(morph: PlayerMorphState) {
    if (!morph.drivesSheet) return
    val p = morph.progress
    if (morph.mode == MorphMode.Crossfade) {
        alpha = p.coerceIn(0f, 1f)
        return
    }
    val top = morph.sheetTop(p)
    translationY = top
    val radius = SheetMorphCorner.toPx() * sheetCornerFraction(p)
    val bottom = morph.coverBottom(p)?.minus(top) ?: size.height
    shape = topRoundedClip(radius, bottom)
    clip = true
}

/** Rounded top corners of [radius] over a rectangle ending at [bottom]. */
private fun topRoundedClip(
    radius: Float,
    bottom: Float,
): Shape =
    GenericShape { size, _ ->
        addRoundRect(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = bottom.coerceAtMost(size.height),
                topLeftCornerRadius = CornerRadius(radius),
                topRightCornerRadius = CornerRadius(radius),
            ),
        )
    }

/**
 * Fades an arriving page element in across its [element] window while Now Playing morphs; the transport's sides also
 * grow from 85 %. At rest (and under the crossfade, and without a morph) it changes nothing.
 */
@Composable
internal fun Modifier.morphReveal(element: MorphElement): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    return graphicsLayer { revealLayer(morph, element) }
}

/** An arriving element's layer at the current progress. */
internal fun GraphicsLayerScope.revealLayer(
    morph: PlayerMorphState,
    element: MorphElement,
) {
    if (!morph.isMorphing) return
    val p = morph.progress
    alpha = reveal(element, p)
    if (element == MorphElement.TransportSides) {
        val scale = sidesScale(p)
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The shell's navigation bar: measured, and slid down by its own height across the morph's first 40 % (canvas `Spec`)
 * as the sheet rises over the place it leaves. At rest under the crossfade and the fade-through.
 */
@Composable
internal fun Modifier.morphNavigationSlide(): Modifier {
    val morph = LocalPlayerMorph.current ?: return this
    return morphNavigationBar().graphicsLayer { navigationSlideLayer(morph) }
}

/** The navigation bar's layer at the current progress. */
internal fun GraphicsLayerScope.navigationSlideLayer(morph: PlayerMorphState) {
    translationY =
        if (morph.entryMotion == EntryMotion.Morph && morph.mode == MorphMode.Morph) {
            morph.navigationSlide(morph.progress)
        } else {
            0f
        }
}

/** Whether the flying parts are drawn: composition reads this once per flight, not per frame. */
@Composable
internal fun rememberShowsFlight(morph: PlayerMorphState): Boolean {
    val shows by remember(morph) { derivedStateOf { morph.isMorphing } }
    return shows
}

internal fun Rect.toMorphRect(): MorphRect = MorphRect(left = left, top = top, width = width, height = height)

/** A rect's centre as a zero-size [MorphRect], so a flight moves centres along the arc. */
internal fun Rect.centreRect(): MorphRect = MorphRect(left = center.x, top = center.y, width = 0f, height = 0f)

/** Whether Now Playing is off the bar along the morph: composition reads this at the ends, not per frame. */
@Composable
internal fun rememberMorphShowsPlayer(morph: PlayerMorphState): Boolean {
    val shows by remember(morph) { derivedStateOf { morph.progress > 0f || morph.isInFlight } }
    return shows
}
