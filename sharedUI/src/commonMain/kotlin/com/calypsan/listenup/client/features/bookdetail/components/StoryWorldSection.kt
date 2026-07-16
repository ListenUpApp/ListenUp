package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.TonalIconTile
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.story_world_entry_card_subtitle_empty
import listenup.composeapp.generated.resources.story_world_entry_card_title

private val STORY_WORLD_SECTION_SHAPE = RoundedCornerShape(24.dp)
private const val GRADIENT_START_ALPHA = 0.16f
private const val BORDER_ALPHA = 0.22f
private val LEADING_TILE_SIZE = 52.dp
private val TITLE_SIZE = 17.sp

/**
 * Story World CTA card shown on Book Detail, linking to the entity/event encyclopedia hub for the
 * book (or its series, if it belongs to one).
 *
 * Same gradient idiom as the Story World hub's own `StorySoFarSeamCard` and
 * `features/seriesdetail`'s local Story World entry card — replicated here rather than shared
 * across features, since each feature package stays independent.
 *
 * Unlike the series-detail entry card, Book Detail's ViewModel does not project an entity count
 * (kept lean — the count is a series-level aggregate, and most books are read standalone or via
 * their series' own entry point), so [subtitle] defaults to a plain static descriptive line rather
 * than a count-driven one. This is an intentional asymmetry with the series card, not an
 * oversight.
 */
@Composable
fun StoryWorldSection(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = stringResource(Res.string.story_world_entry_card_subtitle_empty),
) {
    val gradientStart =
        MaterialTheme.colorScheme.primary
            .copy(alpha = GRADIENT_START_ALPHA)
            .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
    val gradientEnd = MaterialTheme.colorScheme.surfaceContainerLow

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = STORY_WORLD_SECTION_SHAPE,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = BORDER_ALPHA)),
        onClick = onClick,
    ) {
        Row(
            modifier =
                Modifier
                    .background(Brush.linearGradient(listOf(gradientStart, gradientEnd)))
                    .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TonalIconTile(icon = Icons.Outlined.Public, size = LEADING_TILE_SIZE)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.story_world_entry_card_title),
                    fontSize = TITLE_SIZE,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
