package com.calypsan.listenup.client.features.home.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.ListenUpAsyncImage
import com.calypsan.listenup.client.domain.model.Shelf
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_cover_1
import listenup.composeapp.generated.resources.home_cover_2
import listenup.composeapp.generated.resources.home_cover_3
import listenup.composeapp.generated.resources.home_cover_4

private const val DEFAULT_HEX_COLOR = 0xFF6B7280L
private const val ALPHA_MASK = 0xFF000000L
private const val HEX_RADIX = 16
private const val MAX_RGB_LENGTH = 6

/**
 * Card for a shelf in the My Shelves section.
 *
 * Features:
 * - 2x2 book cover grid (or empty state icon)
 * - Shelf name and book count
 * - Press-to-scale animation
 *
 * @param shelf The shelf domain model to display
 * @param onClick Callback when card is clicked
 * @param modifier Optional modifier
 */
@Composable
fun ShelfCard(
    shelf: Shelf,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue =
            when {
                isPressed -> 0.96f
                isFocused -> 1.05f
                else -> 1f
            },
        label = "card_scale",
    )

    // Parse avatar color from hex string (e.g. "#FF6B72" or "#FFFF6B72")
    val avatarColor =
        remember(shelf.ownerAvatarColor) {
            parseHexColor(shelf.ownerAvatarColor)
        }

    Column(
        modifier =
            modifier
                .width(140.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
    ) {
        // Cover grid container
        ShelfCoverGrid(
            coverPaths = shelf.coverPaths,
            color = avatarColor,
            modifier = Modifier.size(140.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Metadata
        Column(modifier = Modifier.padding(horizontal = 2.dp)) {
            Text(
                text = shelf.name,
                style =
                    MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = "${shelf.bookCount} ${if (shelf.bookCount == 1) "book" else "books"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Parse a hex color string (with or without alpha) to a Compose Color.
 * Supports "#RRGGBB" and "#AARRGGBB" formats.
 */
private fun parseHexColor(hex: String?): Color {
    if (hex == null) return Color(DEFAULT_HEX_COLOR.toInt())
    val clean = hex.removePrefix("#")
    val longValue = clean.toLongOrNull(16) ?: return Color(DEFAULT_HEX_COLOR.toInt())
    return if (clean.length <= 6) {
        Color((ALPHA_MASK or longValue).toInt())
    } else {
        Color(longValue.toInt())
    }
}

/**
 * 2x2 grid of book covers for a shelf card.
 *
 * Shows up to 4 book covers in a grid layout. Empty slots are filled
 * with a colored placeholder. If no covers at all, shows a bookshelf icon.
 */
@Composable
private fun ShelfCoverGrid(
    coverPaths: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.medium

    if (coverPaths.isEmpty()) {
        // Empty state — bookshelf icon
        Box(
            modifier =
                modifier
                    .shadow(elevation = 4.dp, shape = shape)
                    .clip(shape)
                    .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.AutoStories,
                contentDescription = null,
                tint = color.copy(alpha = 0.6f),
                modifier = Modifier.size(48.dp),
            )
        }
    } else {
        // 2x2 cover grid
        Box(
            modifier =
                modifier
                    .shadow(elevation = 4.dp, shape = shape)
                    .clip(shape),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    CoverCell(
                        path = coverPaths.getOrNull(0),
                        color = color,
                        contentDescription = stringResource(Res.string.home_cover_1),
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                    CoverCell(
                        path = coverPaths.getOrNull(1),
                        color = color,
                        contentDescription = stringResource(Res.string.home_cover_2),
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    CoverCell(
                        path = coverPaths.getOrNull(2),
                        color = color,
                        contentDescription = stringResource(Res.string.home_cover_3),
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                    CoverCell(
                        path = coverPaths.getOrNull(3),
                        color = color,
                        contentDescription = stringResource(Res.string.home_cover_4),
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * A single cell in the cover grid — either a book cover image or a colored placeholder.
 */
@Composable
private fun CoverCell(
    path: String?,
    color: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    if (path != null) {
        ListenUpAsyncImage(
            path = path,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier =
                modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = color.copy(alpha = 0.3f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
