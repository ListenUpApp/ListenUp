package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.design.components.formatPlaybackTime
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.nowplaying.chapterRowTag
import com.calypsan.listenup.client.features.nowplaying.shortDuration
import com.calypsan.listenup.client.presentation.player.ChapterListFollow
import com.calypsan.listenup.client.presentation.player.ChapterListMove
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_chapters
import listenup.composeapp.generated.resources.player_chapters_count_summary
import listenup.composeapp.generated.resources.player_np_back_to_current
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow as ChapterRowState

/** The chapter panel's list's test tag. */
internal const val CHAPTER_PANEL_TAG = "player-chapter-panel"

/** Room at the list's foot for Back to current chapter: its 48dp target and an 8dp margin either side. */
private val BackButtonReserve = 64.dp

/**
 * The tablet's always-visible chapter list (spec §3.11, §5.3, P33; `NP-AndroidTablet`): a supporting pane beside the
 * player (m3.material.io/foundations/layout/canonical-layouts/supporting-pane) on `surfaceContainer` with extra-large
 * corners. "Chapters" over "75 chapters · 45h 30m", then every chapter as a [PlayerChapterRow]; the current one is
 * coral, says "Playing · 8m left" and carries the chapter's progress bar.
 *
 * It opens centred on the current chapter once it has laid out (§9.2). When the chapter changes it follows only if the
 * old current row was fully in view, so it never yanks the list from under a reader who scrolled away; the rule is the
 * shared [ChapterListFollow]. While the current row is out of view, "Back to current chapter" (a tonal button: a
 * secondary action, m3.material.io/components/buttons) floats at its foot; the list's last rows scroll clear of it.
 * A tap plays from that chapter.
 *
 * The position tick reaches only the row's words (through a derived state that changes once a minute) and its bar
 * (read while it draws), so the list never recomposes on a tick. Mounted only for a book with chapters.
 *
 * @param rows Every chapter of the book.
 * @param currentIndex The playing chapter's zero-based index.
 * @param bookDurationMs The book's length, for the summary.
 * @param progress The playback position, read in the leaves that show it.
 * @param onSeekToChapter Play from the chapter at this zero-based index.
 * @param listState The list's scroll state; hoisted for tests.
 */
@Composable
internal fun NowPlayingChapterPanel(
    rows: List<ChapterRowState>,
    currentIndex: Int,
    bookDurationMs: Long,
    progress: () -> PlayerProgress,
    onSeekToChapter: (Int) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    var lastCurrent by remember { mutableStateOf<Int?>(null) }

    // The opening move and every follow in one effect: the previous row's visibility is read before any scroll, so it
    // answers for the moment of the change (the web page's order). The first run has no previous row: it centres.
    LaunchedEffect(currentIndex) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        val previous = lastCurrent
        val move =
            if (previous == null) {
                ChapterListMove.Recentre
            } else {
                ChapterListFollow.onCurrentChanged(previousRowFullyVisible = listState.isItemFullyVisible(previous))
            }
        lastCurrent = currentIndex
        if (move == ChapterListMove.Recentre) listState.centreOn(currentIndex, animate = previous != null)
    }
    val showsBack by remember(currentIndex) {
        derivedStateOf {
            lastCurrent != null &&
                ChapterListFollow.showsBackToCurrent(
                    currentRowFullyVisible = listState.isItemFullyVisible(currentIndex),
                )
        }
    }
    val leftWords by remember(progress) { derivedStateOf { shortDuration(progress().chapterRemainingMs) } }

    Surface(
        modifier = modifier.semantics { isTraversalGroup = true },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(top = Spacing.lg, start = Spacing.sm, end = Spacing.sm, bottom = Spacing.sm),
        ) {
            Column(modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm)) {
                Text(
                    text = stringResource(Res.string.player_chapters),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text =
                        stringResource(
                            Res.string.player_chapters_count_summary,
                            rows.size,
                            DurationFormatter.hoursMinutes(bookDurationMs.milliseconds),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag(CHAPTER_PANEL_TAG),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    // The last rows scroll clear of Back to current chapter, which floats over the list's foot.
                    contentPadding = PaddingValues(bottom = BackButtonReserve),
                ) {
                    items(rows, key = { it.index }, contentType = { "chapter" }) { row ->
                        val isCurrent = row.index == currentIndex
                        PlayerChapterRow(
                            number = row.number,
                            title = row.title,
                            durationLabel = row.durationMs.milliseconds.formatPlaybackTime(),
                            isCurrent = isCurrent,
                            playingLeft = { leftWords }.takeIf { isCurrent },
                            chapterFraction = { progress().chapterFraction }.takeIf { isCurrent },
                            onClick = { onSeekToChapter(row.index) },
                            modifier = Modifier.testTag(chapterRowTag(row.index)),
                        )
                    }
                }
                BackToCurrentChapter(
                    visible = showsBack,
                    onClick = {
                        haptics.press()
                        scope.launch { listState.centreOn(currentIndex, animate = true) }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.sm),
                )
            }
        }
    }
}

/**
 * "Back to current chapter": a tonal button (m3.material.io/components/buttons, a secondary action) that fades in and
 * out on the motion scheme's fast effects, which the system's "Remove animations" silences. A control, not news, so
 * it is no live region.
 */
@Composable
private fun BackToCurrentChapter(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.fastEffectsSpec()),
        exit = fadeOut(motion.fastEffectsSpec()),
        modifier = modifier,
    ) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(Res.string.player_np_back_to_current))
        }
    }
}
