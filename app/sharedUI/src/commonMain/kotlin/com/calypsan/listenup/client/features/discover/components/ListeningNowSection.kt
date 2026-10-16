package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AdaptiveCardGrid
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.discover.listeningCountLine
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.util.relativeLastActive
import kotlin.time.Clock
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_listening_now
import org.jetbrains.compose.resources.stringResource

private val LISTENER_CARD_WIDTH = 156.dp
private val LISTENER_MIN_CELL = 150.dp

/**
 * "Listening now": who on this server is listening, and to what — the section Discover leads with.
 *
 * Two kinds of row, in one list. Anyone with a live session leads, with a Live chip on the art and
 * "Listening now" under their name; everyone else follows on the book they most recently played,
 * marked with how long ago that was. The fill is the point: with a handful of users nobody is live
 * most of the time, and a section that hides itself whenever presence is empty is indistinguishable
 * from one that is broken.
 *
 * The server owns the split and the order (live by session start, then recent by last-played), and
 * the Room mirror preserves it — this composable renders the list as given. Loading and Error render
 * nothing, as does a genuinely empty roster (PR 6 owns those states).
 *
 * @param wide A wide window: every listener in a wrapping grid, so no person is hidden in a row that
 *   ran out (plan Q2). Otherwise a horizontal rail.
 * @param nowMs the instant relative times are measured against. Read once per composition (the
 *   `DevicesScreen` pattern) so a recomposition cannot make "3 days ago" flicker to "4 days ago"
 *   mid-scroll; passed explicitly by tests.
 */
@Composable
internal fun ListeningNowSection(
    state: CurrentlyListeningUiState,
    onBookClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    isInSelectionMode: Boolean,
    selectedBookIds: Set<String>,
    onBookLongPress: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    nowMs: Long = remember { Clock.System.now().toEpochMilliseconds() },
) {
    val ready = state as? CurrentlyListeningUiState.Ready ?: return
    if (ready.isEmpty) return
    Column(modifier) {
        SectionTitle(
            title = stringResource(Res.string.discover_listening_now),
            subtitle = listeningCountLine(ready.liveCount, ready.recentCount),
            modifier = Modifier.padding(horizontal = Spacing.screenMargin),
        )
        Spacer(Modifier.height(Spacing.md))
        val card: @Composable (CurrentlyListeningUiSession, Dp) -> Unit = { session, width ->
            ListenerCard(
                session = session,
                width = width,
                nowMs = nowMs,
                onBookClick = onBookClick,
                onUserClick = onUserClick,
                isInSelectionMode = isInSelectionMode,
                isSelected = session.bookId in selectedBookIds,
                onBookLongPress = onBookLongPress,
            )
        }
        if (wide) {
            AdaptiveCardGrid(
                items = ready.sessions,
                minCellWidth = LISTENER_MIN_CELL,
                key = { it.sessionId },
                modifier = Modifier.padding(horizontal = Spacing.screenMargin),
            ) { session, width -> card(session, width) }
        } else {
            BrowseCarousel(
                items = ready.sessions,
                itemWidth = LISTENER_CARD_WIDTH,
                itemSpacing = Spacing.md,
                key = { it.sessionId },
            ) { card(it, LISTENER_CARD_WIDTH) }
        }
    }
}

/** The art and title are one link to the book; the person row under it is a sibling link (X16). */
@Composable
private fun ListenerCard(
    session: CurrentlyListeningUiSession,
    width: Dp,
    nowMs: Long,
    onBookClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    isInSelectionMode: Boolean,
    isSelected: Boolean,
    onBookLongPress: ((String) -> Unit)?,
) {
    val marker =
        if (session.isLive) {
            stringResource(Res.string.discover_listening_now)
        } else {
            relativeLastActive(session.lastActiveAt, nowMs)
        }
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        BookCard(
            // The board draws no author under a listener's title: the person row is the second line.
            cover = session.toCoverModel().copy(author = null),
            onClick = { onBookClick(session.bookId) },
            isLive = session.isLive,
            cardWidth = width,
            isInSelectionMode = isInSelectionMode,
            isSelected = isSelected,
            onLongPress = onBookLongPress?.let { cb -> { cb(session.bookId) } },
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button) { onUserClick(session.userId) }
                    .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            UserAvatar(
                userId = session.userId,
                size = AvatarSize.Small,
                fallbackName = session.displayName,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = marker,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (session.isLive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    fontWeight = if (session.isLive) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
