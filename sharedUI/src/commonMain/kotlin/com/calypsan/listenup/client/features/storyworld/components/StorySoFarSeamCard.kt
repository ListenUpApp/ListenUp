package com.calypsan.listenup.client.features.storyworld.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import com.calypsan.listenup.client.design.components.TonalIconTile
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.story_world_story_so_far_seam_body
import listenup.composeapp.generated.resources.story_world_story_so_far_title

private val SEAM_CARD_SHAPE = RoundedCornerShape(24.dp)
private const val GRADIENT_START_ALPHA = 0.16f
private const val BORDER_ALPHA = 0.22f
private val LEADING_TILE_SIZE = 46.dp

/**
 * Brand-tinted gradient seam card teasing the "Story So Far" feature. Clickable (with a trailing
 * chevron) once [onClick] is non-null — the hub passes a target only when the world has a book to
 * recap; otherwise the card stays inert, as it did before Story So Far shipped.
 *
 * @param onClick Called when the card is tapped. Null keeps the card inert (no chevron, no ripple).
 */
@Composable
fun StorySoFarSeamCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val gradientStart =
        MaterialTheme.colorScheme.primary
            .copy(
                alpha = GRADIENT_START_ALPHA,
            ).compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
    val gradientEnd = MaterialTheme.colorScheme.surfaceContainerLow

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = SEAM_CARD_SHAPE,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = BORDER_ALPHA)),
        onClick = onClick ?: {},
        enabled = onClick != null,
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
                    text = stringResource(Res.string.story_world_story_so_far_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.story_world_story_so_far_seam_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
