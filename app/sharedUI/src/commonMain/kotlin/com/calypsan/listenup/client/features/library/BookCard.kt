package com.calypsan.listenup.client.features.library

import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import listenup.composeapp.generated.resources.selection_select
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import com.calypsan.listenup.client.design.util.onSecondaryClick
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.HeldLabel
import com.calypsan.listenup.client.design.components.RestrictedBookMarker
import com.calypsan.listenup.client.design.transitions.bookCoverHeroKey
import com.calypsan.listenup.client.design.components.BookCoverModel
import com.calypsan.listenup.client.design.components.cookieScallopShape
import com.calypsan.listenup.client.design.components.ProgressOverlay
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.library.BookCardStatus
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_time_left
import listenup.composeapp.generated.resources.library_card_finished_length
import listenup.composeapp.generated.resources.library_card_progress_a11y
import listenup.composeapp.generated.resources.library_card_read_by
import listenup.composeapp.generated.resources.common_completed
import listenup.composeapp.generated.resources.common_selected
import listenup.composeapp.generated.resources.library_has_documents_badge
import listenup.composeapp.generated.resources.player_now_playing_wide

// Material 3's hover state-layer opacity.
private const val HOVER_STATE_LAYER_ALPHA = 0.08f

/**
 * Data for an avatar overlay on a book cover.
 *
 * Only the user id is needed: the canonical [UserAvatar] resolves the image, initials, and colour
 * reactively by id from `public_profiles`, so no avatar fields are threaded through here.
 */
data class AvatarOverlayData(
    val userId: String,
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
 * @param cover Bundled cover identity (id, title, author, coverPath, coverHash). Travels
 *   as one unit so a call-site can never silently drop [BookCoverModel.coverHash].
 * @param onClick Callback when card is clicked
 * @param duration Formatted duration string (e.g. "12h 30m")
 * @param subtitle Additional text line (e.g. series name, "Book 1 of X")
 * @param progress Optional progress (0.0-1.0). Shows progress overlay when not finished.
 * @param timeRemaining Optional formatted time remaining (e.g., "2h 15m left")
 * @param isFinished Authoritative completion status. Shows completion badge when true.
 * @param avatarOverlay Optional avatar overlay data for "currently listening" display
 * @param hasDocuments Whether this book has at least one PDF document attached.
 * @param isHeld Whether this book is held for review in the admin inbox — draws [HeldLabel] at the
 *   cover's top-leading corner.
 * @param isInSelectionMode Whether multi-select mode is active
 * @param isSelected Whether this book is currently selected
 * @param onLongPress Callback when card is long-pressed (for entering selection mode)
 * @param cardWidth Fixed width for horizontal rows, or null to fill parent (library grid)
 * @param narrators "Read by …" line. Library grids pass it; their `Adaptive(160.dp)` cells always meet
 *   spec §2.6's 160 dp floor.
 * @param libraryStatus When non-null, this card is a Library card: the status drives the progress mark,
 *   finished badge and last line, and overrides [duration], [progress], [timeRemaining] and [isFinished].
 * @param progressUnderTitle Compact Library grid (Android phone, board `Main`): a wavy indicator under
 *   the title instead of a progress mark on the art.
 * @param modifier Optional modifier for the card
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    cover: BookCoverModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    duration: String? = null,
    subtitle: String? = null,
    progress: Float? = null,
    timeRemaining: String? = null,
    isFinished: Boolean = false,
    isPlaying: Boolean = false,
    avatarOverlay: AvatarOverlayData? = null,
    hasDocuments: Boolean = false,
    isHeld: Boolean = false,
    isInSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    cardWidth: Dp? = null,
    narrators: String? = null,
    libraryStatus: BookCardStatus? = null,
    progressUnderTitle: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val haptics = LocalHaptics.current

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
    val selectLabel = stringResource(Res.string.selection_select)

    Column(
        modifier =
            modifier
                .then(widthModifier)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                // A right-click is the pointer's long press: it opens selection, never the book.
                .onSecondaryClick(
                    onLongPress?.let { longPress ->
                        {
                            haptics.longPress()
                            longPress()
                        }
                    },
                ).then(
                    if (onLongPress != null) {
                        Modifier.combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            // Our gated haptics.longPress() owns the feel; suppress
                            // combinedClickable's built-in long-press haptic so it doesn't double up.
                            hapticFeedbackEnabled = false,
                            // Long-press enters multi-select; say so, or TalkBack offers a bare "long press".
                            onLongClickLabel = selectLabel,
                            onClick = {
                                haptics.press()
                                onClick()
                            },
                            onLongClick = {
                                haptics.longPress()
                                onLongPress()
                            },
                        )
                    } else {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {
                                haptics.press()
                                onClick()
                            },
                        )
                    },
                ).then(
                    // In multi-select a tap toggles the book, so its selection is state, not just a border.
                    if (isInSelectionMode) Modifier.semantics { selected = isSelected } else Modifier,
                ),
    ) {
        // Cover with optional overlays and indicators
        Box {
            val isCompleted = if (libraryStatus != null) libraryStatus is BookCardStatus.Finished else isFinished
            // A Library card's art carries the progress mark only on wide windows; compact grids draw
            // it under the title instead (board `Main`).
            val artProgress =
                when {
                    libraryStatus == null -> if (isCompleted) null else progress
                    libraryStatus is BookCardStatus.InProgress && !progressUnderTitle -> libraryStatus.fraction
                    else -> null
                }
            val artProgressLabel =
                (libraryStatus as? BookCardStatus.InProgress)?.let {
                    stringResource(Res.string.library_card_progress_a11y, (it.fraction * 100).roundToInt())
                }

            // The now-playing book gets a coral frame; selection/focus still win when active.
            val playingBorder = MaterialTheme.colorScheme.primary
            BookCardCover(
                bookId = cover.bookId,
                coverPath = cover.coverPath,
                coverHash = cover.coverHash,
                contentDescription = cover.title,
                title = cover.title,
                author = cover.author.orEmpty(),
                progress = artProgress,
                progressDescription = artProgressLabel,
                timeRemaining = if (isCompleted || libraryStatus != null) null else timeRemaining,
                avatarOverlay = avatarOverlay,
                isHovered = isHovered,
                isSelected = isSelected || isFocused || isPlaying,
                borderColor =
                    when {
                        isSelected -> borderColor
                        isFocused -> focusBorderColor
                        isPlaying -> playingBorder
                        else -> focusBorderColor
                    },
                modifier =
                    if (cardWidth != null) {
                        Modifier.aspectRatio(1f)
                    } else {
                        Modifier.fillMaxWidth().aspectRatio(1f)
                    },
            )

            // Selection checkbox takes precedence over completion / now-playing / documents badges.
            if (isInSelectionMode) {
                SelectionIndicator(
                    isSelected = isSelected || isFocused,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            } else if (isPlaying) {
                NowPlayingBadge(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            } else if (isCompleted) {
                CompletionBadge(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            } else if (hasDocuments) {
                DocumentsBadge(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            }

            // The top-start corner belongs to who-can-see-it, clear of every TopEnd listening badge.
            // Held (the inbox) and the collection lock never mark the same book — the restricted set
            // excludes held books — and the else makes that true even for a frame of disagreement.
            if (isHeld) {
                HeldLabel(modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
            } else {
                RestrictedBookMarker(
                    bookId = cover.bookId,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(if (cardWidth != null) 8.dp else 6.dp))

        // Metadata
        Column(modifier = Modifier.padding(horizontal = if (cardWidth != null) 2.dp else 4.dp)) {
            Text(
                text = cover.title,
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

            cover.author?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            narrators?.let { names ->
                Text(
                    text = stringResource(Res.string.library_card_read_by, names),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (libraryStatus != null) {
                LibraryStatusLine(status = libraryStatus, progressUnderTitle = progressUnderTitle)
            } else {
                duration?.let { dur ->
                    Text(
                        text = dur,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
 * A Library card's last line (spec §2.6): time left in the Library action colour, with a wavy progress
 * mark when [progressUnderTitle]; "Finished · 12h 4m"; or the book's length.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryStatusLine(
    status: BookCardStatus,
    progressUnderTitle: Boolean,
) {
    when (status) {
        is BookCardStatus.InProgress -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (progressUnderTitle) {
                    val progressLabel =
                        stringResource(Res.string.library_card_progress_a11y, (status.fraction * 100).roundToInt())
                    LinearWavyProgressIndicator(
                        progress = { status.fraction },
                        modifier = Modifier.width(56.dp).semantics { contentDescription = progressLabel },
                    )
                }
                Text(
                    text =
                        stringResource(
                            Res.string.book_time_left,
                            DurationFormatter.hoursMinutes(status.timeLeftMs.milliseconds),
                        ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    // The Library action colour (N6); dynamic colour keeps working.
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        is BookCardStatus.Finished -> {
            Text(
                text =
                    stringResource(
                        Res.string.library_card_finished_length,
                        DurationFormatter.hoursMinutes(status.durationMs.milliseconds),
                    ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        is BookCardStatus.NotStarted -> {
            Text(
                text = DurationFormatter.hoursMinutes(status.durationMs.milliseconds),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Scallop badge with a book glyph, shown on the cover of books that have a PDF document. */
@Composable
private fun DocumentsBadge(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(28.dp)
                .clip(cookieScallopShape())
                .background(MaterialTheme.colorScheme.secondary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = stringResource(Res.string.library_has_documents_badge),
            tint = MaterialTheme.colorScheme.onSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Scallop badge with an equalizer glyph, shown on the cover of the currently-playing book. */
@Composable
private fun NowPlayingBadge(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(28.dp)
                .clip(cookieScallopShape())
                .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.GraphicEq,
            contentDescription = stringResource(Res.string.player_now_playing_wide),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Unified cover composable handling all overlay variants.
 *
 * Supports:
 * - BookCoverImage with gradient placeholder fallback (no cover)
 * - Progress overlay (when progress != null and > 0)
 * - Avatar overlay (when avatarOverlay != null)
 * - Selection/focus border
 */
@Composable
private fun BookCardCover(
    bookId: String,
    coverPath: String?,
    coverHash: String? = null,
    contentDescription: String?,
    title: String,
    author: String,
    progress: Float? = null,
    progressDescription: String? = null,
    timeRemaining: String? = null,
    avatarOverlay: AvatarOverlayData? = null,
    isHovered: Boolean = false,
    isSelected: Boolean = false,
    borderColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
) {
    val shape = ContentShapes.card

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
                coverHash = coverHash,
                contentDescription = contentDescription,
                title = title,
                author = author,
                heroKey = bookCoverHeroKey(bookId),
                heroClipShape = shape,
                modifier = Modifier.matchParentSize(),
            )

            // Hover state layer: the Material hover tint over the cover, so a pointer knows what it is on.
            if (isHovered) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = HOVER_STATE_LAYER_ALPHA)),
                )
            }

            // Progress overlay
            if (progress != null && progress > 0f) {
                ProgressOverlay(
                    progress = progress,
                    timeRemaining = timeRemaining,
                    modifier =
                        Modifier.align(Alignment.BottomCenter).then(
                            if (progressDescription != null) {
                                Modifier.semantics { this.contentDescription = progressDescription }
                            } else {
                                Modifier
                            },
                        ),
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
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        label = "selection_bg",
    )

    val iconTint by animateColorAsState(
        targetValue =
            if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
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
 * Completion badge shown when a book is marked as finished — the Material 3 Expressive scallop
 * ([cookieScallopShape], `MaterialShapes.Cookie9Sided`), matching every other badge in the app.
 */
@Composable
private fun CompletionBadge(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(28.dp)
                // Shadow must come from a convex shape: Skia's concave-shadow
                // tessellator can wedge the RenderThread into an ANR on the scallop.
                .shadow(
                    elevation = 3.dp,
                    shape = CircleShape,
                ).background(
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = cookieScallopShape(),
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
