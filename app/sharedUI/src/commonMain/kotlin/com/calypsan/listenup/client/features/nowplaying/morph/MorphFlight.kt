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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.PLAY_BUSY_INDICATOR_TAG
import com.calypsan.listenup.client.design.components.PlayMorphPlayingCorner
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.features.nowplaying.authorLine
import com.calypsan.listenup.client.features.nowplaying.components.MiniLineFit
import com.calypsan.listenup.client.features.nowplaying.components.MiniPlayerLineText
import com.calypsan.listenup.client.features.nowplaying.components.SkipGlyphs
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

/** The bar's line and Now Playing's track (canvas: 3 → 16; the M3 indicator's 4dp here). */
private val BarLineThickness = 4.dp
private val PageTrackThickness = 16.dp

/** The scrubber's handle. */
private val ThumbWidth = 4.dp
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
        LineFlight(morph, progress)
        CoverFlight(morph, state)
        TitleFlight(morph, state.book.title, isPage = false)
        TitleFlight(morph, state.book.title, isPage = true)
        if (playFlies(state, line)) PlayFlight(morph, state.primary)
        BarRiders(morph, state, line, progress, skipBackwardSec)
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
                    val page = morph.anchors.pageRect(MorphPart.Cover)
                    if (bar == null || page == null || page.width <= 0f || !morph.anchors.isReady) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val at = flight(bar.toMorphRect(), page.toMorphRect(), p)
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
                    val width = own?.width?.roundToInt() ?: 0
                    val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }.graphicsLayer {
                    val p = morph.progress
                    val bar = morph.anchors.barRect(MorphPart.Title)
                    val page = morph.anchors.pageRect(MorphPart.Title)
                    if (bar == null || page == null || !morph.anchors.isReady) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val centre = flight(bar.centreRect(), page.centreRect(), p)
                    translationX = centre.left - size.width / 2f
                    translationY = centre.top - size.height / 2f
                    val scale = if (isPage) lerp(1f / ratio, 1f, p) else lerp(1f, ratio, p)
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
                    val width = at?.width?.roundToInt()?.coerceAtLeast(0) ?: 0
                    val height = at?.height?.roundToInt()?.coerceAtLeast(0) ?: 0
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
    val page = anchors.pageRect(MorphPart.Play) ?: return null
    val at = flight(bar.toMorphRect(), page.toMorphRect(), progress)
    return Rect(Offset(at.left, at.top), Size(at.width, at.height))
}

@Composable
private fun LineFlight(
    morph: PlayerMorphState,
    progress: () -> PlayerProgress,
) {
    val colors = MaterialTheme.colorScheme
    Spacer(
        Modifier.fillMaxSize().testTag(MORPH_LINE_TAG).drawBehind {
            val p = morph.progress
            val bar = morph.anchors.barRect(MorphPart.Line) ?: return@drawBehind
            val page = morph.anchors.pageRect(MorphPart.Line) ?: return@drawBehind
            if (!morph.anchors.isReady) return@drawBehind
            val at = flight(bar.toMorphRect(), page.toMorphRect(), p)
            val centreY = flight(bar.centreRect(), page.centreRect(), p).top
            val thickness =
                lerp(BarLineThickness.toPx(), PageTrackThickness.toPx(), reveal(MorphElement.ScrubberThickness, p))
            val radius = CornerRadius(thickness / 2f * MorphElement.ScrubberThickness.window.of(p))
            val top = centreY - thickness / 2f
            drawRoundRect(colors.surfaceContainerHighest, Offset(at.left, top), Size(at.width, thickness), radius)
            val filled = at.width * progress().chapterFraction
            drawRoundRect(colors.primary, Offset(at.left, top), Size(filled, thickness), radius)
            val thumbAlpha = reveal(MorphElement.ScrubberThumb, p)
            if (thumbAlpha > 0f) {
                val thumb = Size(ThumbWidth.toPx(), ThumbHeight.toPx())
                drawRoundRect(
                    color = colors.primary,
                    topLeft = Offset(at.left + filled - thumb.width / 2f, centreY - thumb.height / 2f),
                    size = thumb,
                    cornerRadius = CornerRadius(thumb.width / 2f),
                    alpha = thumbAlpha,
                )
            }
        },
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
        val width = rect?.width?.roundToInt() ?: 0
        val height = rect?.height?.roundToInt() ?: 0
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
        val width = page?.width?.roundToInt() ?: 0
        val height = page?.height?.roundToInt() ?: 0
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }

private const val MIN_SCALE = 0.01f
