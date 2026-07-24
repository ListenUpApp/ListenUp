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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.domain.model.Shelf
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.genre_book_count
import listenup.composeapp.generated.resources.genre_books_count
import listenup.composeapp.generated.resources.home_private_shelf
import org.jetbrains.compose.resources.stringResource

private val ShelfCardWidth = 180.dp
private val ShelfCardHeight = 116.dp

/**
 * A color-blocked shelf card: a filled container with a soft blob, the shelf icon, its name, and the
 * book count. Colors are supplied by the caller so a row of shelves cycles through the
 * primary/tertiary/secondary container roles.
 *
 * @param shelf The shelf domain model.
 * @param containerColor The card's fill color (a `*Container` role).
 * @param contentColor The matching `on*Container` color for icon + text.
 * @param onClick Card click.
 * @param modifier Optional modifier.
 * @param fillWidth When true the card fills its parent width (desktop vertical stack); otherwise it
 *   takes a fixed width (mobile carousel).
 */
@Composable
fun ShelfCard(
    shelf: Shelf,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
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
        label = "shelf_card_scale",
    )

    val widthModifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(ShelfCardWidth)

    Box(
        modifier =
            modifier
                .then(widthModifier)
                .height(ShelfCardHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(ContentShapes.card)
                .background(containerColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
    ) {
        // Soft decorative blob, bottom-right.
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 24.dp, y = 24.dp)
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.12f)),
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(26.dp),
            )
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (shelf.isPrivate) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = stringResource(Res.string.home_private_shelf),
                            tint = contentColor.copy(alpha = 0.8f),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = shelf.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text =
                        if (shelf.bookCount == 1) {
                            stringResource(Res.string.genre_book_count, shelf.bookCount)
                        } else {
                            stringResource(Res.string.genre_books_count, shelf.bookCount)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.8f),
                )
            }
        }
    }
}
