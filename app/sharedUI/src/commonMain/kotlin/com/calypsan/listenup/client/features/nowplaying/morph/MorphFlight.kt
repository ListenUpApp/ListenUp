package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.PLAY_BUSY_INDICATOR_TAG
import com.calypsan.listenup.client.design.components.PlayMorphPlayingCorner
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.ScrubberHandleGap
import com.calypsan.listenup.client.design.components.ScrubberHandleWidth
import com.calypsan.listenup.client.design.components.ScrubberTrackGeometry
import com.calypsan.listenup.client.design.components.drawScrubberWave
import com.calypsan.listenup.client.design.components.scrubberTrackGeometry
import com.calypsan.listenup.client.design.components.scrubberWaveAmplitude
import com.calypsan.listenup.client.features.nowplaying.isAudible
import com.calypsan.listenup.client.features.nowplaying.authorLine
import com.calypsan.listenup.client.features.nowplaying.components.MiniLineFit
import com.calypsan.listenup.client.features.nowplaying.components.MiniPlayerLineText
import com.calypsan.listenup.client.features.nowplaying.components.SkipGlyphs
import com.calypsan.listenup.client.presentation.player.BackPreviewWeight
import com.calypsan.listenup.client.presentation.player.MiniPlayerLine
import com.calypsan.listenup.client.presentation.player.MiniPlayerLines
import com.calypsan.listenup.client.presentation.player.MorphElement
import com.calypsan.listenup.client.presentation.player.MorphWindow
import com.calypsan.listenup.client.presentation.player.PlayerCards
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.client.presentation.player.SleepCountdowns
import com.calypsan.listenup.client.presentation.player.fade
import com.calypsan.listenup.client.presentation.player.flight
import com.calypsan.listenup.client.presentation.player.reveal
import kotlin.math.roundToInt

/** Test tag: the cover that flies between the bar and Now Playing. */
internal const val MORPH_COVER_TAG = "morph-cover"

/** Test tag: the play button that flies between the bar and Now Playing. */
internal const val MORPH_PLAY_TAG = "morph-play"

/** Test tag: the progress line that becomes the scrubber. */
internal const val MORPH_LINE_TAG = "morph-line"

/** The bar's cover corners (`shapes.medium`) and Now Playing's (`shapes.large`). */
private val BarCoverCorner = 20.dp
private val PageCoverCorner = 28.dp

/** The scrubber's handle height; its width is [ScrubberHandleWidth]. */
private val ThumbHeight = 44.dp

/** The play glyph on the bar (M3 icon button) and on Now Playing (96 × 0.42). */
private val BarGlyph = 24.dp
private val PageGlyph = 40.dp

/** The bar's title fades out across this window, Now Playing's fades in across the next (Call C6). */
private val BarTitleOut = MorphWindow(0.35f, 0.5f)
private val PageTitleIn = MorphWindow(0.5f, 0.65f)

/**
 * The parts that fly between the bar and Now Playing (canvas `MorphAndroid`): the cover, the title pair, the play
 * button and the progress line, plus the bar's second line and skip-back riding the sheet up as they fade. Composed
 * once when a flight starts; every frame after is a redraw, because each part reads the progress only in its layer,
 * draw and layout lambdas.
 */
@Composable
internal fun PlayerMorphFlight(
    morph: PlayerMorphState,
    state: PlayerUiState.Loaded,
    progress: () -> PlayerProgress,
    skipBackwardSec: Int,
) {
    if (!rememberShowsFlight(morph)) return
    val line = MiniPlayerLines.of(state)
    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        LineFlight(morph, progress, isPlaying = state.isAudible)
        CoverFlight(morph, state)
        TitleFlight(morph, state.book.title, isPage = false)
        TitleFlight(morph, state.book.title, isPage = true)
        if (playFlies(state, line)) PlayFlight(morph, state.primary)
        BarRiders(morph = morph, state = state, line = line, progress = progress, skipBackwardSec = skipBackwardSec)
    }
}

/**
 * Whether the play button flies: the bar draws a play, pause or busy button (not Keep listening or a way out) and Now
 * Playing draws its transport (not a state card in its place). Pure.
 */
internal fun playFlies(
    state: PlayerUiState.Loaded,
    line: MiniPlayerLine,
): Boolean =
    PlayerCards.of(state) == null &&
        line != MiniPlayerLine.FadingOut &&
        state.primary in setOf(PrimaryControl.Pause, PrimaryControl.Play, PrimaryControl.TapToPlay, PrimaryControl.Busy)

@Composable
private fun CoverFlight(
    morph: PlayerMorphState,
    state: PlayerUiState.Loaded,
) {
    BookCoverImage(
        bookId = state.book.id,
        coverPath = state.book.coverPath,
        coverHash = state.book.coverHash,
        contentDescription = null,
        title = state.book.title,
        author = state.book.authorLine,
        modifier =
            Modifier
                .fixedToPage(morph, MorphPart.Cover)
                .graphicsLayer {
                    val p = morph.progress
                    val bar = morph.anchors.barRect(MorphPart.Cover)
                    val page = morph.anchors.pageRect(MorphPart.Cover)?.takeIf { it.width > 0f }
                    val target = morph.pageTarget(MorphPart.Cover)
                    if (bar == null || page == null || target == null || !morph.anchors.isReady) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val at = flight(bar.toMorphRect(), target.toMorphRect(), p)
                    alpha = 1f
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = at.left
                    translationY = at.top
                    scaleX = at.width / page.width
                    scaleY = at.height / page.height
                    val radius = lerp(BarCoverCorner.toPx(), PageCoverCorner.toPx(), p.coerceIn(0f, 1f))
                    shape = RoundedCornerShape(radius / scaleX.coerceAtLeast(MIN_SCALE))
                    clip = true
                }.testTag(MORPH_COVER_TAG),
    )
}

@Composable
private fun TitleFlight(
    morph: PlayerMorphState,
    title: String,
    isPage: Boolean,
) {
    val barStyle = MaterialTheme.typography.titleMedium
    val pageStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    val ratio = pageStyle.fontSize.value / barStyle.fontSize.value
    Text(
        text = title,
        style = if (isPage) pageStyle else barStyle,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = if (isPage) TextAlign.Center else TextAlign.Start,
        maxLines = if (isPage) 2 else 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .layout { measurable, _ ->
                    morph.progress
                    val own =
                        if (isPage) {
                            morph.anchors.pageRect(
                                MorphPart.Title,
                            )
                        } else {
                            morph.anchors.barRect(MorphPart.Title)
                        }
                    val width = own?.run { width.roundToInt() } ?: 0
                    val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }.graphicsLayer {
                    val p = morph.progress
                    val bar = morph.anchors.barRect(MorphPart.Title)
                    val page = morph.anchors.pageRect(MorphPart.Title)?.takeIf { it.width > 0f }
                    val target = morph.pageTarget(MorphPart.Title)
                    if (bar == null || page == null || target == null || !morph.anchors.isReady) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val centre = flight(bar.centreRect(), target.centreRect(), p)
                    translationX = centre.left - size.width / 2f
                    translationY = centre.top - size.height / 2f
                    // A committed back's shrink, carried at the page end.
                    val carried = lerp(1f, target.width / page.width, p)
                    val scale = carried * if (isPage) lerp(1f / ratio, 1f, p) else lerp(1f, ratio, p)
                    scaleX = scale
                    scaleY = scale
                    alpha = if (isPage) PageTitleIn.of(p) else 1f - BarTitleOut.of(p)
                },
    )
}

@Composable
private fun PlayFlight(
    morph: PlayerMorphState,
    primary: PrimaryControl,
) {
    val colors = MaterialTheme.colorScheme
    val isPause = primary == PrimaryControl.Pause
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .testTag(MORPH_PLAY_TAG)
                .layout { measurable, _ ->
                    val at = morph.playRect()
                    val width = at?.run { width.roundToInt().coerceAtLeast(0) } ?: 0
                    val height = at?.run { height.roundToInt().coerceAtLeast(0) } ?: 0
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(width, height) { placeable.place(0, 0) }
                }.graphicsLayer {
                    val at = morph.playRect()
                    val bar = morph.anchors.barRect(MorphPart.Play)
                    if (at == null || bar == null || !morph.anchors.isReady) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    alpha = 1f
                    translationX = at.left
                    translationY = at.top
                    val pageCorner = if (isPause) PlayMorphPlayingCorner.toPx() else at.height / 2f
                    val barCorner = minOf(bar.width, bar.height) / 2f
                    shape = RoundedCornerShape(lerp(barCorner, pageCorner, morph.progress.coerceIn(0f, 1f)))
                    clip = true
                }.background(colors.primary),
    ) {
        val glyphScale =
            Modifier.graphicsLayer {
                val scale = lerp(BarGlyph / PageGlyph, 1f, morph.progress)
                scaleX = scale
                scaleY = scale
            }
        if (primary == PrimaryControl.Busy) {
            ListenUpLoadingIndicator(
                size = PageGlyph,
                color = colors.onPrimary,
                modifier = glyphScale.testTag(PLAY_BUSY_INDICATOR_TAG),
            )
        } else {
            Icon(
                imageVector = if (isPause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = glyphScale.size(PageGlyph),
            )
        }
    }
}

/** The play button's rect at the current progress, host px, or null before both ends are measured. */
private fun PlayerMorphState.playRect(): Rect? {
    val bar = anchors.barRect(MorphPart.Play) ?: return null
    val page = pageTarget(MorphPart.Play) ?: return null
    val at = flight(bar.toMorphRect(), page.toMorphRect(), progress)
    return Rect(Offset(at.left, at.top), Size(at.width, at.height))
}

/**
 * The progress line in flight, drawn from [lineFlightParts]: the inactive track, the filled part as the scrubber's own
 * wave while [isPlaying] (flat when paused, and by M3's rule near either end), and the handle fading in. It waves on
 * the morph's shared phase ([PlayerMorphState.wavePhase]), so the bar's wave, the flying line and the scrubber are one
 * wave that never jumps. Mirrored in right-to-left layouts.
 */
@Composable
private fun LineFlight(
    morph: PlayerMorphState,
    progress: () -> PlayerProgress,
    isPlaying: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val wavePath = remember { Path() }
    val inactivePath = remember { Path() }
    Spacer(
        Modifier.fillMaxSize().testTag(MORPH_LINE_TAG).drawBehind {
            val fraction = progress().chapterFraction
            val line =
                morph.lineFlightParts(fraction, isRtl = layoutDirection == LayoutDirection.Rtl, density = this)
                    ?: return@drawBehind
            val parts = line.geometry
            drawScrubberWave(
                path = inactivePath,
                from = parts.inactiveStart,
                to = parts.trackEnd,
                amplitude = 0f,
                phase = 0f,
                color = colors.surfaceContainerHighest,
                centreY = line.centreY,
            )
            drawScrubberWave(
                path = wavePath,
                from = parts.trackStart,
                to = parts.waveEnd,
                amplitude = if (isPlaying) scrubberWaveAmplitude(fraction) else 0f,
                phase = morph.wavePhase.current(),
                color = colors.primary,
                centreY = line.centreY,
            )
            if (line.handleShown > 0f) {
                val thumb = Size(ScrubberHandleWidth.toPx(), ThumbHeight.toPx())
                drawRoundRect(
                    color = colors.primary,
                    topLeft = Offset(parts.handleCentre - thumb.width / 2f, line.centreY - thumb.height / 2f),
                    size = thumb,
                    cornerRadius = CornerRadius(thumb.width / 2f),
                    alpha = line.handleShown,
                )
            }
        },
    )
}

/** Where the flying line's parts are now, in host px: its [geometry] along x, its [centreY], how far its handle shows. */
internal data class LineFlightParts(
    val geometry: ScrubberTrackGeometry,
    val centreY: Float,
    val handleShown: Float,
)

/**
 * The flying line at the current progress, or null before both ends are measured: the rect flies from the bar's line
 * to Now Playing's slider, and the slider's inset and handle gaps ease in with the handle, so at 1 every part stands
 * where the slider draws it ([scrubberTrackGeometry]) and at 0 the line is the bar's, edge to edge.
 */
internal fun PlayerMorphState.lineFlightParts(
    fraction: Float,
    isRtl: Boolean,
    density: Density,
): LineFlightParts? {
    val bar = anchors.barRect(MorphPart.Line) ?: return null
    val page = pageTarget(MorphPart.Line) ?: return null
    if (!anchors.isReady) return null
    val p = progress
    val at = flight(bar.toMorphRect(), page.toMorphRect(), p)
    val handleShown = reveal(MorphElement.ScrubberThumb, p)
    val geometry =
        with(density) {
            scrubberTrackGeometry(
                width = at.width,
                fraction = fraction,
                handleWidth = ScrubberHandleWidth.toPx(),
                handleGap = ScrubberHandleGap.toPx(),
                handleShown = handleShown,
                isRtl = isRtl,
            )
        }.offsetBy(at.left)
    return LineFlightParts(
        geometry = geometry,
        centreY = flight(bar.centreRect(), page.centreRect(), p).top,
        handleShown = handleShown,
    )
}

/** The bar's second line and skip-back: they keep their place on the sheet's top edge as it rises, and fade. */
@Composable
private fun BarRiders(
    morph: PlayerMorphState,
    state: PlayerUiState.Loaded,
    line: MiniPlayerLine,
    progress: () -> PlayerProgress,
    skipBackwardSec: Int,
) {
    val tinted = line is MiniPlayerLine.Problem && line.isTinted
    MiniPlayerLineText(
        line = line,
        progress = progress,
        sleepRemainingMs = { SleepCountdowns.remainingMs(state.sleepTimer, progress(), state.settings.speed) },
        fit = MiniLineFit.WithTimeLeft,
        color = if (tinted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.ridesSheet(morph, BarDetail.SecondLine, MorphElement.BarSecondLine),
    )
    if (state.showsSkipBack) {
        Box(
            Modifier.ridesSheet(morph, BarDetail.SkipBack, MorphElement.BarSkipBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SkipGlyphs.backward(skipBackwardSec),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(BarGlyph),
            )
        }
    }
}

private fun Modifier.ridesSheet(
    morph: PlayerMorphState,
    detail: BarDetail,
    element: MorphElement,
): Modifier =
    layout { measurable, _ ->
        morph.progress
        val rect = morph.anchors.barRect(detail)
        val width = rect?.run { width.roundToInt() } ?: 0
        val height = rect?.run { height.roundToInt() } ?: 0
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }.graphicsLayer {
        val p = morph.progress
        val rect = morph.anchors.barRect(detail)
        val barTop = morph.anchors.barBounds?.top
        if (rect == null || barTop == null || !morph.anchors.isReady) {
            alpha = 0f
            return@graphicsLayer
        }
        translationX = rect.left
        translationY = rect.top - p.coerceIn(0f, 1f) * barTop
        alpha = fade(element, p)
    }

/**
 * Lays the part out at Now Playing's size, so it is drawn sharp at the end and scaled down at the bar. Reads the
 * progress, which re-runs layout (never composition) each frame, so the size is right from the frame both ends exist.
 */
private fun Modifier.fixedToPage(
    morph: PlayerMorphState,
    part: MorphPart,
): Modifier =
    layout { measurable, _ ->
        morph.progress
        val page = morph.anchors.pageRect(part)
        val width = page?.run { width.roundToInt() } ?: 0
        val height = page?.run { height.roundToInt() } ?: 0
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }

private const val MIN_SCALE = 0.01f

/**
 * Where Now Playing's [part] is drawn now, as the flight's page end: its sheet-space rect under the back preview a
 * committed back carried into the close (scaled about the sheet's centre and drifted, as the sheet's own layer draws
 * it), so the flying parts start from the shrunken page rather than jumping to where the page would be at full size.
 */
internal fun PlayerMorphState.pageTarget(part: MorphPart): Rect? {
    val page = anchors.pageRect(part) ?: return null
    val weight = backPreview.takeIf { it != BackPreviewWeight.None }?.at(progress) ?: return page
    val sheet = anchors.sheet?.takeIf { it.isAttached } ?: return page
    val centreX = sheet.size.width / 2f
    val centreY = sheet.size.height / 2f

    fun x(value: Float) = centreX + (value - centreX) * weight.scale + weight.translationX

    fun y(value: Float) = centreY + (value - centreY) * weight.scale
    return Rect(left = x(page.left), top = y(page.top), right = x(page.right), bottom = y(page.bottom))
}
