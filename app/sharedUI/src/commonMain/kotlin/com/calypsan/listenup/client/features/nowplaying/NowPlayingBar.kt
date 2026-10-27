package com.calypsan.listenup.client.features.nowplaying

import com.calypsan.listenup.client.features.nowplaying.morph.morphBarFocus
import com.calypsan.listenup.client.features.nowplaying.morph.dragToOpen
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import com.calypsan.listenup.client.design.LocalCompositionProbe
import com.calypsan.listenup.client.features.nowplaying.morph.LocalPlayerMorph
import com.calypsan.listenup.client.presentation.player.MorphEnd
import com.calypsan.listenup.client.features.nowplaying.morph.morphBarSurface
import com.calypsan.listenup.client.features.nowplaying.morph.morphAnchor
import com.calypsan.listenup.client.features.nowplaying.morph.MorphPart
import com.calypsan.listenup.client.features.nowplaying.morph.BarDetail
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.util.isLargeFontScale
import com.calypsan.listenup.client.features.nowplaying.components.Ctrl
import com.calypsan.listenup.client.features.nowplaying.components.MiniBookSwitch
import com.calypsan.listenup.client.features.nowplaying.components.MiniLineFit
import com.calypsan.listenup.client.features.nowplaying.components.MiniPlayerLineText
import com.calypsan.listenup.client.features.nowplaying.components.MiniPrimaryButton
import com.calypsan.listenup.client.features.nowplaying.components.SkipGlyphs
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.MiniPlayerLine
import com.calypsan.listenup.client.presentation.player.MiniPlayerLines
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.SleepCountdowns
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_mini_open
import listenup.composeapp.generated.resources.player_mini_reconnecting_a11y
import listenup.composeapp.generated.resources.player_mini_resume
import listenup.composeapp.generated.resources.player_skip_backward
import org.jetbrains.compose.resources.stringResource

/** A mini-player control's glyph on the phone bar and the toolbar: the M3 icon-button glyph. */
internal val MiniPlayerGlyphSize = 24.dp

/** The progress line's slot: the wave's own height (M3 `WaveHeight`), so a phase change never moves the row. */
internal val MiniProgressSlotHeight = 10.dp

/** The docked toolbar's row (m3.material.io/components/toolbars: 64dp). */
internal val MiniBarRowHeight = 64.dp

/** The phone bar's whole height above any system inset. */
internal val MiniBarHeight = MiniProgressSlotHeight + MiniBarRowHeight

/** The phone bar's play/pause container: the wide 56×48 (§5.1 M10). */
internal val MiniPhonePlaySize = DpSize(56.dp, 48.dp)

/** The phone bar's cover. */
private val MiniPhoneCoverSize = 48.dp

/** Test tag: the wavy chapter line, drawn only while the book is playing. */
internal const val MINI_PROGRESS_WAVY_TAG = "mini-progress-wavy"

/** Test tag: the flat chapter line, drawn in every other phase. */
internal const val MINI_PROGRESS_FLAT_TAG = "mini-progress-flat"

/** Test tag: the body, the one target that opens Now Playing. */
internal const val MINI_BODY_TAG = "mini-body"

/** Test tag: the decorative cover inside the body. */
internal const val MINI_COVER_TAG = "mini-cover"

/**
 * The phone mini player (spec §5.1, board `Main`): a flat docked toolbar, full width on `surfaceContainerHigh`,
 * directly above the 64dp navigation bar — or, on a detail screen, at the bottom edge with its row padded clear of
 * the gesture area ([bottomInset]). A chapter line along the top is the wave while playing, flat otherwise.
 *
 * The body (cover, title, line) is one target, "Open Now Playing", and back N and the main button are its siblings,
 * never inside it (§8). A failure tints the bar `errorContainer`; the bar never hides while a book is loaded (gap 6).
 *
 * @param onPrimary The main button's action, decided by [PlayerUiState.Loaded.primary].
 * @param onKeepListening The main button's action during the sleep fade, where it reads Keep listening: cancel the
 * timer and restore the volume.
 * @param bottomInset The system inset the bar runs under and pads its row clear of; none above the navigation bar.
 */
@Composable
fun NowPlayingBar(
    state: PlayerUiState,
    progress: () -> PlayerProgress,
    isExpanded: Boolean,
    onTap: () -> Unit,
    onPrimary: () -> Unit,
    onSkipBack: () -> Unit,
    onKeepListening: () -> Unit,
    skipBackwardSec: Int,
    modifier: Modifier = Modifier,
    bottomInset: WindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
) {
    // Under a player host's morph the bar stays laid out while Now Playing covers it: the sheet rises out of it and
    // lands back on it, so it must neither slide away nor reflow the shell. It leaves semantics and focus instead.
    val morph = LocalPlayerMorph.current
    val coveredByPlayer = isExpanded && morph != null
    AnimatedVisibility(
        visible = state is PlayerUiState.Loaded && (!isExpanded || morph != null),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        val loaded = state as? PlayerUiState.Loaded ?: return@AnimatedVisibility
        val line = MiniPlayerLines.of(loaded)
        val tinted = line is MiniPlayerLine.Problem && line.isTinted
        Surface(
            shape = RectangleShape,
            color = if (tinted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (tinted) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .morphBarSurface()
                    // The morph's drag where the bar becomes Now Playing; the swipe-up trigger where it fades through.
                    .then(
                        if (morph?.entryMotion == EntryMotion.Morph) {
                            Modifier.dragToOpen(morph)
                        } else {
                            Modifier.swipeUpToExpand(onTap)
                        },
                    )
                    .then(if (coveredByPlayer) Modifier.coveredByPlayer() else Modifier),
        ) {
            // The container stays; only its content moves when the book changes.
            MiniBookSwitch(loaded, Modifier.windowInsetsPadding(bottomInset)) { shown ->
                PhoneBarContent(
                    state = shown,
                    progress = progress,
                    onOpen = onTap,
                    onPrimary = onPrimary,
                    onSkipBack = onSkipBack,
                    onKeepListening = onKeepListening,
                    skipBackwardSec = skipBackwardSec,
                )
            }
        }
    }
}

@Composable
private fun PhoneBarContent(
    state: PlayerUiState.Loaded,
    progress: () -> PlayerProgress,
    onOpen: () -> Unit,
    onPrimary: () -> Unit,
    onSkipBack: () -> Unit,
    onKeepListening: () -> Unit,
    skipBackwardSec: Int,
) {
    LocalCompositionProbe.current("PhoneBarContent")
    val line = MiniPlayerLines.of(state)
    Column {
        MiniProgressLine(
            isPlaying = state.phase == PlayerPhase.Playing,
            progress = progress,
            modifier = Modifier.fillMaxWidth().height(MiniProgressSlotHeight),
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(MiniBarRowHeight).padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MiniBody(
                state = state,
                line = line,
                progress = progress,
                onOpen = onOpen,
                fit = MiniLineFit.WithTimeLeft,
                coverSize = MiniPhoneCoverSize,
            )
            if (state.showsSkipBack) {
                Box(Modifier.morphAnchor(BarDetail.SkipBack)) {
                    Ctrl(
                        icon = SkipGlyphs.backward(skipBackwardSec),
                        contentDescription = stringResource(Res.string.player_skip_backward, skipBackwardSec),
                        onClick = onSkipBack,
                        size = MinTouchTarget,
                        iconSize = MiniPlayerGlyphSize,
                        tint = LocalContentColor.current,
                    )
                }
            }
            MiniPrimaryButton(
                primary = state.primary,
                bookTitle = state.book.title,
                onClick = onPrimary,
                size = MiniPhonePlaySize,
                playLabel = miniPlayLabel(line),
                busyLabel = miniBusyLabel(line),
                onKeepListening = onKeepListening.takeIf { line == MiniPlayerLine.FadingOut },
                modifier = Modifier.morphAnchor(MorphEnd.Bar, MorphPart.Play),
            )
        }
    }
}

/** "Resume" for Android's background refusal (§4.6), whose primary is Play; null keeps "Play". */
@Composable
internal fun miniPlayLabel(line: MiniPlayerLine): String? =
    if (line is MiniPlayerLine.Problem && line.error is PlaybackError.BlockedInBackground) {
        stringResource(Res.string.player_mini_resume)
    } else {
        null
    }

/** "Reconnecting. Pause" for the busy control while a dropped connection recovers (board `Reconnecting`). */
@Composable
internal fun miniBusyLabel(line: MiniPlayerLine): String? =
    if (line == MiniPlayerLine.Reconnecting) stringResource(Res.string.player_mini_reconnecting_a11y) else null

/**
 * The open target: cover, title and line, one node named "Open Now Playing" (§8). The cover is decorative (the title
 * beside it names the book) and steps aside at a large font, where the title needs the width more than the bar needs
 * the picture.
 */
@Composable
internal fun RowScope.MiniBody(
    state: PlayerUiState.Loaded,
    line: MiniPlayerLine,
    progress: () -> PlayerProgress,
    onOpen: () -> Unit,
    fit: MiniLineFit,
    coverSize: Dp,
    modifier: Modifier = Modifier,
) {
    val tinted = line is MiniPlayerLine.Problem && line.isTinted
    Row(
        modifier =
            modifier
                .weight(1f)
                .heightIn(min = MinTouchTarget)
                .clip(MaterialTheme.shapes.medium)
                .morphBarFocus()
                .clickable(
                    onClickLabel = stringResource(Res.string.player_mini_open),
                    role = Role.Button,
                    onClick = onOpen,
                ).testTag(MINI_BODY_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!isLargeFontScale()) {
            BookCoverImage(
                bookId = state.book.id,
                coverPath = state.book.coverPath,
                coverHash = state.book.coverHash,
                // The title beside it names the book; a described cover (or its placeholder's lettering) would read
                // it twice, so the cover carries no semantics at all.
                contentDescription = null,
                title = state.book.title,
                author = state.book.authorLine,
                modifier =
                    Modifier
                        .size(coverSize)
                        .clip(MaterialTheme.shapes.medium)
                        .testTag(MINI_COVER_TAG)
                        .morphAnchor(MorphEnd.Bar, MorphPart.Cover)
                        .clearAndSetSemantics {},
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = state.book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.morphAnchor(MorphEnd.Bar, MorphPart.Title),
            )
            MiniPlayerLineText(
                line = line,
                progress = progress,
                sleepRemainingMs = { SleepCountdowns.remainingMs(state.sleepTimer, progress(), state.settings.speed) },
                fit = fit,
                modifier = Modifier.morphAnchor(BarDetail.SecondLine),
                color =
                    if (tinted) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

/** The chapter line along the bar's top edge: the wave only for the book that is playing (F6), flat otherwise. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MiniProgressLine(
    isPlaying: Boolean,
    progress: () -> PlayerProgress,
    modifier: Modifier,
) {
    Box(modifier.morphAnchor(MorphEnd.Bar, MorphPart.Line), contentAlignment = Alignment.Center) {
        if (isPlaying) {
            LinearWavyProgressIndicator(
                progress = { progress().chapterFraction },
                modifier = Modifier.fillMaxWidth().testTag(MINI_PROGRESS_WAVY_TAG),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        } else {
            LinearProgressIndicator(
                progress = { progress().chapterFraction },
                modifier = Modifier.fillMaxWidth().testTag(MINI_PROGRESS_FLAT_TAG),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
            )
        }
    }
}

/** Out of reach while Now Playing covers it: no semantics for a screen reader, no stop for the keyboard. */
private fun Modifier.coveredByPlayer(): Modifier =
    clearAndSetSemantics {}
        .focusProperties { onEnter = { cancelFocusChange() } }
        .focusGroup()
