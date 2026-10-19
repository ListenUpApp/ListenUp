package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing

/** One caption line's bar height; with [CAPTION_GAP] a line takes the 16dp a caption line does. */
private val CAPTION_BAR = 12.dp
private val CAPTION_GAP = 4.dp

/** The activity and leaderboard row height a [RowsSkeleton] row stands in for. */
private val SKELETON_ROW_HEIGHT = 64.dp

private val BarShape = RoundedCornerShape(6.dp)

/** Later caption lines are shorter than the first, as a title is longer than its byline. */
private const val SHORT_CAPTION_FRACTION = 0.6f

/**
 * A rail of square cards with their caption lines, standing in for a section's cards while it loads.
 *
 * Spec §3.8: a section keeps its place while loading and says plainly when it failed; Home's sections use the
 * same pieces. The skeleton is silent to TalkBack: the section heading above it already says what is coming.
 *
 * @param cardWidth The real cards' width on a phone rail; null fills the row in equal columns (tablet grids).
 * @param captionLines How many caption lines sit under each real card (title, author, meta…).
 * @param count How many cards to stand in for.
 */
@Composable
fun RailSkeleton(
    cardWidth: Dp?,
    captionLines: Int,
    modifier: Modifier = Modifier,
    count: Int = 4,
) {
    Row(
        modifier = modifier.fillMaxWidth().clipToBounds().clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        repeat(count) {
            val cardModifier = if (cardWidth != null) Modifier.requiredWidth(cardWidth) else Modifier.weight(1f)
            Column(cardModifier, verticalArrangement = Arrangement.spacedBy(CAPTION_GAP)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, ContentShapes.card),
                )
                repeat(captionLines) { line ->
                    SkeletonBar(widthFraction = if (line == 0) 1f else SHORT_CAPTION_FRACTION, height = CAPTION_BAR)
                }
            }
        }
    }
}

/**
 * Rows of an avatar and two text lines, standing in for an activity feed or a leaderboard while it loads.
 *
 * Spec §3.8: a section keeps its place while loading and says plainly when it failed; Home's sections use the
 * same pieces. Silent to TalkBack, like [RailSkeleton].
 *
 * @param rows How many rows to stand in for.
 * @param leadingSize The real rows' avatar size.
 */
@Composable
fun RowsSkeleton(
    rows: Int,
    modifier: Modifier = Modifier,
    leadingSize: Dp = 48.dp,
) {
    Column(modifier = modifier.fillMaxWidth().clearAndSetSemantics {}) {
        repeat(rows) {
            Row(
                modifier = Modifier.fillMaxWidth().height(SKELETON_ROW_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Box(
                    Modifier
                        .size(leadingSize)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SkeletonBar(widthFraction = 1f, height = CAPTION_BAR)
                    SkeletonBar(widthFraction = SHORT_CAPTION_FRACTION, height = CAPTION_BAR)
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(
    widthFraction: Float,
    height: Dp,
) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant, BarShape),
    )
}
