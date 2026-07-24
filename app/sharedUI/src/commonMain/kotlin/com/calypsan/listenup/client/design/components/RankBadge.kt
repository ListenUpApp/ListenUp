package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Medal tones for the podium (rank 1-3). These intentionally live outside the M3 color scheme:
// gold/silver/bronze are universal physical-medal colours, not theme roles, so the leaderboard reads
// as a podium on both light and dark backgrounds. Everything else uses scheme colours.
private val GoldFill = Color(0xFFF5C84BL)
private val GoldInk = Color(0xFF3A2A00L)
private val SilverFill = Color(0xFFC9CDD6L)
private val SilverInk = Color(0xFF26282EL)
private val BronzeFill = Color(0xFFE0A06AL)
private val BronzeInk = Color(0xFF3A1F0CL)

private val BADGE_SIZE = 36.dp

/**
 * Which visual tier a [RankBadge] renders. The podium ranks (1-3) get a medal-toned fill; everything
 * else falls back to a neutral surface circle. Pure mapping — held separate from the composable so the
 * tier logic is unit-testable without a Compose host.
 */
enum class RankTier {
    /** Rank 1 — gold. */
    Gold,

    /** Rank 2 — silver. */
    Silver,

    /** Rank 3 — bronze. */
    Bronze,

    /** Rank 4+ — neutral surface circle. */
    Neutral,
}

/**
 * Map a 1-based [rank] to its visual [RankTier]. Ranks 1/2/3 are the podium (gold/silver/bronze);
 * everything else is [RankTier.Neutral].
 */
fun rankTier(rank: Int): RankTier =
    when (rank) {
        1 -> RankTier.Gold
        2 -> RankTier.Silver
        3 -> RankTier.Bronze
        else -> RankTier.Neutral
    }

/**
 * A circular rank badge for any ranked list. Ranks 1/2/3 render with gold/silver/bronze medal tones;
 * every other rank renders as a neutral [surfaceContainerHigh][androidx.compose.material3.ColorScheme.surfaceContainerHigh]
 * circle with [onSurfaceVariant][androidx.compose.material3.ColorScheme.onSurfaceVariant] text.
 *
 * @param rank The 1-based position to display.
 * @param modifier Modifier for the badge.
 */
@Composable
fun RankBadge(
    rank: Int,
    modifier: Modifier = Modifier,
) {
    val (fill, ink) =
        when (rankTier(rank)) {
            RankTier.Gold -> {
                GoldFill to GoldInk
            }

            RankTier.Silver -> {
                SilverFill to SilverInk
            }

            RankTier.Bronze -> {
                BronzeFill to BronzeInk
            }

            RankTier.Neutral -> {
                MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
            }
        }

    Badge(fill = fill, ink = ink, label = rank.toString(), size = BADGE_SIZE, modifier = modifier)
}

@Composable
private fun Badge(
    fill: Color,
    ink: Color,
    label: String,
    size: Dp,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            color = ink,
        )
    }
}
