package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import com.calypsan.listenup.client.design.MinTouchTarget
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.features.nowplaying.components.Ctrl
import com.calypsan.listenup.client.features.nowplaying.components.LabelPill
import com.calypsan.listenup.client.features.nowplaying.components.PrimaryControlFab
import com.calypsan.listenup.client.features.nowplaying.components.SkipGlyphs
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_cover_a11y
import listenup.composeapp.generated.resources.player_expand
import listenup.composeapp.generated.resources.player_skip_backward
import listenup.composeapp.generated.resources.player_skip_forward
import org.jetbrains.compose.resources.stringResource

/** Height of the single-row docked mini-player bar (medium/expanded width, TV). */
internal val DockedNowPlayingBarHeight = 72.dp

/**
 * Docked mini-player bar for medium/expanded widths (tablet, unfolded foldable, desktop) and TV.
 *
 * A full-width single-row bar ([DockedNowPlayingBarHeight] tall, [surfaceContainerLow] background,
 * large rounded corners), everything on one line, vertically centred:
 * - 48dp cover + book title / chapter info (bounded so the scrubber gets the flexible width)
 * - transport controls: skip back / play-pause FAB / skip forward
 * - inline seekable [WavySeekBar] with elapsed and remaining time labels (fills remaining width)
 * - speed pill + expand button
 *
 * Renders whenever a book is loaded, including when it failed or cannot play (the second line then says why,
 * gap 6); animated in/out with slide + fade.
 *
 * @param onPrimary The main button's action, decided by [PlayerUiState.Loaded.primary].
 */
@Composable
fun DockedNowPlayingBar(
    state: PlayerUiState,
    progress: () -> PlayerProgress,
    isExpanded: Boolean,
    onTap: () -> Unit,
    onPrimary: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeedClick: () -> Unit,
    skipBackwardSec: Int,
    skipForwardSec: Int,
    modifier: Modifier = Modifier,
) {
    val isVisible = state is PlayerUiState.Loaded && !isExpanded

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        val focusScale by animateFloatAsState(
            targetValue = if (isFocused) 1.02f else 1f,
            label = "docked_player_focus_scale",
        )
        val focusBorderColor = MaterialTheme.colorScheme.primary
        val barShape = MaterialTheme.shapes.large

        Surface(
            onClick = onTap,
            interactionSource = interactionSource,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { role = Role.Button }
                    .swipeUpToExpand(onTap)
                    .graphicsLayer {
                        scaleX = focusScale
                        scaleY = focusScale
                    }.then(
                        if (isFocused) {
                            Modifier.border(
                                width = 2.dp,
                                color = focusBorderColor,
                                shape = barShape,
                            )
                        } else {
                            Modifier
                        },
                    ),
            shape = barShape,
            // Tonal, not shadowed: chrome rises by container level (DESIGN.md, Books-Cast-Shadows).
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            if (state is PlayerUiState.Loaded) {
                ActiveDockedContent(
                    state = state,
                    progress = progress,
                    onPrimary = onPrimary,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onSeek = onSeek,
                    onSpeedClick = onSpeedClick,
                    onExpand = onTap,
                    skipBackwardSec = skipBackwardSec,
                    skipForwardSec = skipForwardSec,
                )
            }
        }
    }
}

@Composable
private fun ActiveDockedContent(
    state: PlayerUiState.Loaded,
    progress: () -> PlayerProgress,
    onPrimary: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeedClick: () -> Unit,
    onExpand: () -> Unit,
    skipBackwardSec: Int,
    skipForwardSec: Int,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(DockedNowPlayingBarHeight)
                .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Cover + title / chapter — bounded so the inline scrubber gets the flexible width.
        BookCoverImage(
            bookId = state.book.id,
            coverPath = state.book.coverPath,
            coverHash = state.book.coverHash,
            contentDescription = stringResource(Res.string.player_cover_a11y),
            title = state.book.title,
            author = state.book.authorLine,
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
        )
        Column(modifier = Modifier.widthIn(max = 200.dp)) {
            Text(
                text = state.book.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Show the chapter's own title; no "Ch. N · " prefix — the title is often itself
            // "Chapter N", and chapterIndex counts front-matter tracks (Introduction, Epigraph),
            // so the prefix produced confusing, offset labels like "Ch. 3 · Chapter 1". When the book
            // cannot play, this line says why instead (gap 6).
            Text(
                text = state.statusLine(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Transport. Back N only where there is a settled position to go back from (§3.5).
        if (state.showsSkipBack) {
            Ctrl(
                icon = SkipGlyphs.backward(skipBackwardSec),
                contentDescription = stringResource(Res.string.player_skip_backward, skipBackwardSec),
                onClick = onSkipBack,
                size = MinTouchTarget,
                iconSize = MiniPlayerGlyphSize,
            )
        }
        // The main button, drawn from the shared primary: a play request in flight shows as Busy
        // without hiding this bar; a failure offers its way out.
        PrimaryControlFab(
            primary = state.primary,
            onClick = onPrimary,
            size = MinTouchTarget,
        )
        Ctrl(
            icon = SkipGlyphs.forward(skipForwardSec),
            contentDescription = stringResource(Res.string.player_skip_forward, skipForwardSec),
            onClick = onSkipForward,
            size = MinTouchTarget,
            iconSize = MiniPlayerGlyphSize,
        )

        // Inline scrubber — fills the remaining width. It reads [progress] internally so a
        // position tick recomposes only the scrubber row, not this whole docked bar.
        ChapterScrubberRow(
            progress = progress,
            isPlaying = state.isAudible,
            onSeek = onSeek,
            modifier = Modifier.weight(1f),
        )

        // Speed + expand.
        LabelPill(
            label = formatPlaybackSpeed(state.settings.speed),
            onClick = onSpeedClick,
        )
        Ctrl(
            icon = Icons.Default.OpenInFull,
            contentDescription = stringResource(Res.string.player_expand),
            onClick = onExpand,
            size = MinTouchTarget,
            iconSize = MiniPlayerGlyphSize,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            pressFeedback = false,
        )
    }
}

/** Elapsed label · [WavySeekBar] · -remaining label, filling available width. */
@Composable
private fun ChapterScrubberRow(
    progress: () -> PlayerProgress,
    isPlaying: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val current = progress()
        val elapsedTime = current.chapterPositionMs.milliseconds.formatPlaybackTime()
        val remainingMs = (current.chapterDurationMs - current.chapterPositionMs).coerceAtLeast(0)
        val remainingTime = "-${remainingMs.milliseconds.formatPlaybackTime()}"

        Text(
            text = elapsedTime,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WavySeekBar(
            progress = current.chapterFraction,
            onSeek = onSeek,
            modifier = Modifier.weight(1f),
            enabled = true,
            isPlaying = isPlaying,
        )
        Text(
            text = remainingTime,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
