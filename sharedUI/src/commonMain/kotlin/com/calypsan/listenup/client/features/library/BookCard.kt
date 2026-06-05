package com.calypsan.listenup.client.features.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.ProgressOverlay
import com.calypsan.listenup.client.design.components.UserAvatar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_selected
import listenup.composeapp.generated.resources.common_completed

/**
 * Data for an avatar overlay on a book cover.
 */
data class AvatarOverlayData(
    val userId: String,
    val displayName: String,
    val avatarType: String,
    val avatarValue: String?,
    val avatarColor: String,
)

/**
 * Unified floating book card with editorial design.
 *
 * Cover art is the hero. No container boxing.
 * A soft glow radiates from behind, creating depth without harsh shadows.
 * Press interaction uses scale animation for tactile feedback.
 *
 * Supports all book card variants:
 * - Library grid: progress, completion badge, selection, focus border
 * - Continue Listening: progress overlay with time remaining
 * - Currently Listening: avatar overlay showing who's listening
 * - Discover: basic cover with optional subtitle (e.g. series name)
 * - Recently Added: basic cover with title/author
 *
 * @param bookId Unique book identifier
 * @param title Book title
 * @param coverPath Local file path to cover image
 * @param blurHash BlurHash string for placeholder
 * @param onClick Callback when card is clicked
 * @param authorName Author name(s) to display below title
 * @param duration Formatted duration string (e.g. "12h 30m")
 * @param subtitle Additional text line (e.g. series name, "Book 1 of X")
 * @param progress Optional progress (0.0-1.0). Shows progress overlay when not finished.
 * @param timeRemaining Optional formatted time remaining (e.g., "2h 15m left")
 * @param isFinished Authoritative completion status. Shows completion badge when true.
 * @param avatarOverlay Optional avatar overlay data for "currently listening" display
 * @param isInSelectionMode Whether multi-select mode is active
 * @param isSelected Whether this book is currently selected
 * @param onLongPress Callback when card is long-pressed (for entering selection mode)
 * @param cardWidth Fixed width for horizontal rows, or null to fill parent (library grid)
 * @param modifier Optional modifier for the card
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    bookId: String,
    title: String,
    coverPath: String?,
    blurHash: String?,
    onClick: () -> Unit,
    authorName: String? = null,
    duration: String? = null,
    subtitle: String? = null,
    progress: Float? = null,
    timeRemaining: String? = null,
    isFinished: Boolean = false,
    avatarOverlay: AvatarOverlayData? = null,
    isInSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    cardWidth: Dp? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val hapticFeedback = LocalHapticFeedback.current

    // Animate scale for press, focus, and selection
    val scale by animateFloatAsState(
        targetValue =
            when {
                isPressed -> 0.96f
                isFocused -> 1.05f
                isSelected -> 0.98f
                else -> 1f
            },
        label = "card_scale",
    )

    // Animate border color for selection
    val borderColor by animateColorAsState(
        targetValue =
            if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0f)
            },
        label = "border_color",
    )

    // Animate border for focus (TV/keyboard navigation)
    val focusBorderColor by animateColorAsState(
        targetValue =
            if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0f)
            },
        label = "focus_border_color",
    )

    val widthModifier = if (cardWidth != null) Modifier.width(cardWidth) else Modifier

    Column(
        modifier =
            modifier
                .then(widthModifier)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.then(
                    if (onLongPress != null) {
                        Modifier.combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                            onLongClick = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPress()
                            },
                        )
                    } else {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                    },
                ),
    ) {
        // Cover with optional overlays and indicators
        Box {
            val isCompleted = isFinished

            BookCardCover(
                bookId = bookId,
                coverPath = coverPath,
                blurHash = blurHash,
                contentDescription = title,
                progress = if (isCompleted) null else progress,
                timeRemaining = if (isCompleted) null else timeRemaining,
                avatarOverlay = avatarOverlay,
                isSelected = isSelected || isFocused,
                borderColor = if (isSelected) borderColor else focusBorderColor,
                modifier =
                    if (cardWidth != null) {
                        Modifier.aspectRatio(1f)
                    } else {
                        Modifier.fillMaxWidth().aspectRatio(1f)
                    },
            )

            // Selection checkbox takes precedence over completion badge
            if (isInSelectionMode) {
                SelectionIndicator(
                    isSelected = isSelected || isFocused,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                )
            } else if (isCompleted) {
                CompletionBadge(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(if (cardWidth != null) 8.dp else 6.dp))

        // Metadata
        Column(modifier = Modifier.padding(horizontal = if (cardWidth != null) 2.dp else 4.dp)) {
            Text(
                text = title,
                style =
                    if (cardWidth != null) {
                        MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp,
                        )
                    } else {
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp,
                        )
                    },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            authorName?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            duration?.let { dur ->
                Text(
                    text = dur,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            subtitle?.let { sub ->
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Unified cover composable handling all overlay variants.
 *
 * Supports:
 * - BookCoverImage with blurHash placeholder
 * - Gradient placeholder fallback (no cover/blurHash)
 * - Progress overlay (when progress != null and > 0)
 * - Avatar overlay (when avatarOverlay != null)
 * - Selection/focus border
 */
@Composable
private fun BookCardCover(
    bookId: String,
    coverPath: String?,
    blurHash: String?,
    contentDescription: String?,
    progress: Float? = null,
    timeRemaining: String? = null,
    avatarOverlay: AvatarOverlayData? = null,
    isSelected: Boolean = false,
    borderColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)

    // Outer Box allows avatar to overflow the clipped cover area
    Box(modifier = modifier) {
        // Cover container with shadow and clip
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .shadow(elevation = 6.dp, shape = shape)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                width = 3.dp,
                                color = borderColor,
                                shape = shape,
                            )
                        } else {
                            Modifier
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            // Always render BookCoverImage — handles server URL fallback for missing local files
            BookCoverImage(
                bookId = bookId,
                coverPath = coverPath,
                blurHash = blurHash,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
            )

            // Progress overlay
            if (progress != null && progress > 0f) {
                ProgressOverlay(
                    progress = progress,
                    timeRemaining = timeRemaining,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // Avatar overlay in bottom-right corner
        if (avatarOverlay != null) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-4).dp, y = (-4).dp)
                        .size(36.dp)
                        .shadow(elevation = 4.dp, shape = CircleShape)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp),
            ) {
                // Mini badge on a book cover — Small fills the 32dp inner area
                UserAvatar(
                    userId = avatarOverlay.userId,
                    size = AvatarSize.Small,
                )
            }
        }
    }
}

/**
 * Selection indicator shown when in multi-select mode.
 * Shows a filled checkmark when selected, empty circle when not.
 */
@Composable
private fun SelectionIndicator(
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val backgroundColor by animateColorAsState(
        targetValue =
            if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
            },
        label = "selection_bg",
    )

    val iconTint by animateColorAsState(
        targetValue =
            if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            },
        label = "selection_icon",
    )

    Box(
        modifier =
            modifier
                .size(28.dp)
                .shadow(
                    elevation = 2.dp,
                    shape = CircleShape,
                ).background(
                    color = backgroundColor,
                    shape = CircleShape,
                ).then(
                    if (!isSelected) {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            shape = CircleShape,
                        )
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = stringResource(Res.string.common_selected),
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Organic blob shape inspired by Material 3 Expressive design.
 */
private class SquigglyShape(
    private val wobbleAmount: Float = 0.12f,
    private val waves: Int = 6,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path()
        val centerX = size.width / 2
        val centerY = size.height / 2
        val baseRadius = minOf(size.width, size.height) / 2

        val points = 72
        for (i in 0..points) {
            val angle = i.toFloat() / points * 2 * PI
            val wobble =
                1f + wobbleAmount * sin(waves * angle).toFloat() +
                    wobbleAmount / 2 * cos((waves * 2 + 1) * angle).toFloat()
            val radius = baseRadius * wobble

            val x = centerX + radius * cos(angle).toFloat()
            val y = centerY + radius * sin(angle).toFloat()

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()

        return Outline.Generic(path)
    }
}

/**
 * Completion badge shown when a book is marked as finished.
 */
@Composable
private fun CompletionBadge(modifier: Modifier = Modifier) {
    val squigglyShape = remember { SquigglyShape(wobbleAmount = 0.1f, waves = 5) }

    Box(
        modifier =
            modifier
                .size(28.dp)
                .shadow(
                    elevation = 3.dp,
                    shape = squigglyShape,
                ).background(
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = squigglyShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = stringResource(Res.string.common_completed),
            tint = MaterialTheme.colorScheme.onTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}
