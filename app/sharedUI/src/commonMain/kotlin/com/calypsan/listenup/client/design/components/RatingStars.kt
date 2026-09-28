package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.StarHalf
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.domain.ListenerRatingLimits
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.rating_stars_a11y
import listenup.composeapp.generated.resources.rating_stars_label
import listenup.composeapp.generated.resources.rating_stars_unrated
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val STAR_COUNT = 5

/** A read-only star, sized to sit beside a line of text. */
private val ReadOnlyStarSize = 16.dp

/** An input star, big enough to hit with a thumb in half-star steps. */
private val InputStarSize = 44.dp

/** The input's minimum touch height. */
private val MinInputHeight = 48.dp

/**
 * Five stars filled in halves from [halfStars] (2..10; 0 draws five empty stars).
 *
 * Read-only when [onHalfStarsChange] is null — small, for reader lines and summaries. With a
 * callback it is an input: tap or drag across the stars to set a half-star rating, with a
 * selection tick for every half crossed, and TalkBack can adjust it like a slider. Either way it
 * announces itself through [ListenerRatingLimits.starsLabel], so every platform says the same thing.
 *
 * @param halfStars The rating in half-star units.
 * @param modifier Optional modifier.
 * @param onHalfStarsChange Called with the new rating (2..10) when the listener picks one; null for
 *   read-only stars.
 * @param starSize The size of each star.
 */
@Composable
fun RatingStars(
    halfStars: Int,
    modifier: Modifier = Modifier,
    onHalfStarsChange: ((Int) -> Unit)? = null,
    starSize: Dp = if (onHalfStarsChange == null) ReadOnlyStarSize else InputStarSize,
) {
    val spoken =
        stringResource(Res.string.rating_stars_a11y, ListenerRatingLimits.starsLabel(halfStars.toDouble()))
    val stars =
        if (onHalfStarsChange == null) {
            modifier.clearAndSetSemantics { contentDescription = spoken }
        } else {
            modifier
                .heightIn(min = MinInputHeight)
                .ratingInput(
                    halfStars = halfStars,
                    description = stringResource(Res.string.rating_stars_label),
                    state =
                        if (halfStars < ListenerRatingLimits.MIN_HALF_STARS) {
                            stringResource(Res.string.rating_stars_unrated)
                        } else {
                            spoken
                        },
                    onHalfStarsChange = onHalfStarsChange,
                )
        }
    val filled = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = stars, verticalAlignment = Alignment.CenterVertically) {
        repeat(STAR_COUNT) { index ->
            val glyph = starGlyph(halfStars, index)
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = if (glyph == Icons.Rounded.StarBorder) empty else filled,
                modifier = Modifier.size(starSize),
            )
        }
    }
}

/**
 * Tap, drag and TalkBack adjustment for the input variant of [RatingStars]. TalkBack hears a static
 * [description] ("Rating") and the value as [state], so the stars are read once, not twice.
 */
@Composable
private fun Modifier.ratingInput(
    halfStars: Int,
    description: String,
    state: String,
    onHalfStarsChange: (Int) -> Unit,
): Modifier {
    val haptics = LocalHaptics.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val current by rememberUpdatedState(halfStars)
    val onChange by rememberUpdatedState(onHalfStarsChange)

    // Reports [picked] if it differs from [previous] (one tick per half crossed) and returns it.
    fun report(
        picked: Int,
        previous: Int,
    ): Int {
        if (picked != previous) {
            haptics.selectionTick()
            onChange(picked)
        }
        return picked
    }

    fun pickAt(
        x: Float,
        width: Int,
    ): Int = halfStarsAt(if (isRtl) width - x else x, width.toFloat())

    return this
        .pointerInput(isRtl) { detectTapGestures { report(pickAt(it.x, size.width), current) } }
        .pointerInput(isRtl) {
            // The last half this drag reported, tracked here rather than read back from
            // composition, so a fast drag ticks once per half even before the parent recomposes.
            var last = current
            detectHorizontalDragGestures(
                onDragStart = { last = report(pickAt(it.x, size.width), current) },
                onHorizontalDrag = { change, _ -> last = report(pickAt(change.position.x, size.width), last) },
            )
        }.clearAndSetSemantics {
            contentDescription = description
            stateDescription = state
            // 0..10 so an unrated control reports 0 ("Not rated"); a step up from there asks for 1,
            // which setProgress lifts to one star.
            progressBarRangeInfo =
                ProgressBarRangeInfo(
                    current = halfStars.coerceIn(0, ListenerRatingLimits.MAX_HALF_STARS).toFloat(),
                    range = 0f..ListenerRatingLimits.MAX_HALF_STARS.toFloat(),
                    steps = ListenerRatingLimits.MAX_HALF_STARS - 1,
                )
            setProgress { target ->
                val picked =
                    target
                        .roundToInt()
                        .coerceIn(ListenerRatingLimits.MIN_HALF_STARS, ListenerRatingLimits.MAX_HALF_STARS)
                report(picked, current)
                true
            }
        }
}

/** The glyph for star [index] (0-based) of a [halfStars] rating. */
private fun starGlyph(
    halfStars: Int,
    index: Int,
): ImageVector {
    val starEnd = (index + 1) * 2
    return when {
        halfStars >= starEnd -> Icons.Rounded.Star
        halfStars == starEnd - 1 -> Icons.AutoMirrored.Rounded.StarHalf
        else -> Icons.Rounded.StarBorder
    }
}

/**
 * The half-star rating a touch at [x] sets on five equal stars spanning [width], measured from the
 * start edge: the left half of a star counts half of it, the right half counts it whole. Clamped to
 * one..five stars, so a touch at the very start still rates the book.
 */
internal fun halfStarsAt(
    x: Float,
    width: Float,
): Int {
    val starWidth = width / STAR_COUNT
    return ceil(x / starWidth * 2)
        .toInt()
        .coerceIn(ListenerRatingLimits.MIN_HALF_STARS, ListenerRatingLimits.MAX_HALF_STARS)
}
