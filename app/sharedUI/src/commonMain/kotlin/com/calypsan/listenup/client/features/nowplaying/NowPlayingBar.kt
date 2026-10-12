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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.nowplaying.components.Ctrl
import com.calypsan.listenup.client.features.nowplaying.components.PrimaryControlFab
import com.calypsan.listenup.client.features.nowplaying.components.SkipGlyphs
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.design.util.isLargeFontScale
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_cover_a11y
import listenup.composeapp.generated.resources.player_skip_backward
import org.jetbrains.compose.resources.stringResource

/** A mini-player control's glyph — the size the bars were drawn at, kept while the targets grew to 48dp. */
internal val MiniPlayerGlyphSize = 20.dp

/**
 * Floating mini-player card docked above the bottom navigation.
 *
 * M3 Expressive styling per the MiniPlayerMobile design:
 * - [surfaceContainerHigh] card with large rounded corners
 * - Cover thumbnail · title + chapter line · skip-back · play/pause squircle
 * - Non-interactive wavy progress strip along the bottom (chapter progress)
 *
 * Visible whenever a book is loaded and [isExpanded] is false — including when it failed or cannot play: the
 * second line then says why and the main button offers the way out (gap 6). Tapping anywhere expands to the
 * full-screen player via [onTap].
 *
 * @param onPrimary The main button's action, decided by [PlayerUiState.Loaded.primary].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NowPlayingBar(
    state: PlayerUiState,
    progress: () -> PlayerProgress,
    isExpanded: Boolean,
    onTap: () -> Unit,
    onPrimary: () -> Unit,
    onSkipBack: () -> Unit,
    skipBackwardSec: Int,
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
            targetValue = if (isFocused) 1.05f else 1f,
            label = "mini_player_focus_scale",
        )
        val focusBorderShape = MaterialTheme.shapes.large

        Surface(
            onClick = onTap,
            interactionSource = interactionSource,
            modifier =
                Modifier
                    .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm)
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
                                color = MaterialTheme.colorScheme.primary,
                                shape = focusBorderShape,
                            )
                        } else {
                            Modifier
                        },
                    ),
            shape = MaterialTheme.shapes.large,
            // Tonal, not shadowed: the highest container level lifts it off the content it floats
            // over; only covers cast shadows (DESIGN.md, Books-Cast-Shadows).
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            if (state is PlayerUiState.Loaded) {
                MiniPlayerContent(
                    state = state,
                    progress = progress,
                    onPrimary = onPrimary,
                    onSkipBack = onSkipBack,
                    skipBackwardSec = skipBackwardSec,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MiniPlayerContent(
    state: PlayerUiState.Loaded,
    progress: () -> PlayerProgress,
    onPrimary: () -> Unit,
    onSkipBack: () -> Unit,
    skipBackwardSec: Int,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Cover thumbnail — 48dp, rounded 12dp corners. At a large font it steps aside: the title already
            // names the book, and the title needs the width more than the bar needs the picture.
            if (!isLargeFontScale()) {
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
                            .clip(MaterialTheme.shapes.small),
                )

                Spacer(Modifier.width(4.dp))
            }

            // Title + chapter info column — grows to fill available space
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.book.title,
                    style = MaterialTheme.typography.titleMedium,
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

            // Skip-back control (single skip on the phone mini-player), only where there is a settled
            // position to go back from (§3.5).
            if (state.showsSkipBack) {
                Ctrl(
                    icon = SkipGlyphs.backward(skipBackwardSec),
                    contentDescription = stringResource(Res.string.player_skip_backward, skipBackwardSec),
                    onClick = onSkipBack,
                    size = MinTouchTarget,
                    iconSize = MiniPlayerGlyphSize,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            // The main button, drawn from the shared primary. No drop shadow — the bar's rounded
            // Surface would clip it. A play request in flight shows as Busy without hiding this bar.
            PrimaryControlFab(
                primary = state.primary,
                onClick = onPrimary,
                size = 48.dp,
            )
        }

        // Thin, non-interactive chapter progress strip. A plain bar (not the wavy indicator) reads
        // cleaner at the mini-player's small height — the expressive wave is reserved for the
        // full-screen player and the desktop bar, where it has the vertical room to register.
        LinearProgressIndicator(
            progress = { progress().chapterFraction },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
    }
}
