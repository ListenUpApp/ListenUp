package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import kotlin.time.Duration.Companion.milliseconds
import com.calypsan.listenup.client.design.theme.HeroInk
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_abridged
import listenup.composeapp.generated.resources.book_detail_unabridged
import org.jetbrains.compose.resources.stringResource

/** Tone variants for [StatChip]. */
enum class StatChipTone {
    /** Neutral surface — default for most chips. */
    Surface,

    /** Tertiary container — for the lead/accent chip. */
    Tertiary,

    /** Secondary container — Book Detail's hero stats, quiet beside the primary actions. */
    Accent,
}

/**
 * The hero's quick stats as chips: length · year · Unabridged/Abridged (spec D9/D10). Text only, on the
 * [StatChipTone.Accent] container: Android takes no cover-derived accent, so the chips stay on a colour role and
 * follow Material You when it is on.
 */
@Composable
internal fun HeroStatChips(
    durationMs: Long,
    year: Int?,
    abridged: Boolean,
    centered: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.spacedBy(
                6.dp,
                if (centered) Alignment.CenterHorizontally else Alignment.Start,
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatChip(
            icon = null,
            text = DurationFormatter.hoursMinutesAlways(durationMs.milliseconds),
            tone = StatChipTone.Accent,
        )
        year?.takeIf { it > 0 }?.let { StatChip(icon = null, text = it.toString(), tone = StatChipTone.Accent) }
        StatChip(
            icon = null,
            text = stringResource(if (abridged) Res.string.book_detail_abridged else Res.string.book_detail_unabridged),
            tone = StatChipTone.Accent,
        )
    }
}

/**
 * Individual stat chip with icon and text.
 *
 * @param onHeroBand When true, the chip is coloured for the wide hero's `primaryContainer` band: the
 *   accent ([StatChipTone.Tertiary]) chip becomes a solid `primary` fill (the lead chip), the neutral
 *   chips a faint `onPrimaryContainer` wash — both legible against the colour band. When false (the
 *   default, used by the compact layout) the chip keeps its surface-background styling.
 */
@Composable
fun StatChip(
    icon: (@Composable () -> Unit)?,
    text: String,
    tone: StatChipTone = StatChipTone.Surface,
    onHeroBand: Boolean = false,
) {
    // On the colour band: the accent (Tertiary) chip leads with a solid primary fill; neutral chips
    // read as a faint onPrimaryContainer wash so they sit quietly on the band. Off the band (compact
    // layout) the chips keep their surface-background styling.
    val containerColor =
        when (tone) {
            StatChipTone.Surface -> {
                if (onHeroBand) {
                    HeroInk.wash()
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            }

            StatChipTone.Tertiary -> {
                if (onHeroBand) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                }
            }

            StatChipTone.Accent -> {
                MaterialTheme.colorScheme.secondaryContainer
            }
        }
    val contentColor =
        when (tone) {
            StatChipTone.Surface -> {
                if (onHeroBand) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            }

            StatChipTone.Tertiary -> {
                if (onHeroBand) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                }
            }

            StatChipTone.Accent -> {
                MaterialTheme.colorScheme.onSecondaryContainer
            }
        }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon?.invoke()
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
