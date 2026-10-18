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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.presentation.discover.DiscoverViewer
import com.calypsan.listenup.client.design.components.InlineRetry
import com.calypsan.listenup.client.design.components.RailSkeleton
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import listenup.composeapp.generated.resources.discover_error_listening
import listenup.composeapp.generated.resources.discover_listening_as_of
import listenup.composeapp.generated.resources.discover_nobody_listening
import com.calypsan.listenup.client.util.relativeLastActive
import com.calypsan.listenup.client.util.relativeLastActiveInSentence
import kotlin.time.Clock
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_listener_live_a11y
import listenup.composeapp.generated.resources.discover_listener_recent_a11y
import listenup.composeapp.generated.resources.discover_listening_now
import listenup.composeapp.generated.resources.selection_select
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
 * the Room mirror preserves it — this composable renders the list as given. Every other state keeps
 * the heading (spec §3.8): see [listeningNowBody].
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
    community: CommunitySize,
    viewer: DiscoverViewer?,
    onInvite: (() -> Unit)?,
    onRetry: () -> Unit,
    onBookClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    isInSelectionMode: Boolean,
    selectedBookIds: Set<String>,
    onBookLongPress: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    nowMs: Long = remember { Clock.System.now().toEpochMilliseconds() },
) {
    val ready = state as? CurrentlyListeningUiState.Ready
    Column(modifier) {
        SectionTitle(
            title = stringResource(Res.string.discover_listening_now),
            subtitle = ready?.let { listeningCountLine(it.liveCount, it.recentCount) },
            modifier = Modifier.padding(horizontal = Spacing.screenMargin),
        )
        val staleSince = ready?.takeIf { it.isStale && !it.isEmpty }?.observedAtMs
        if (staleSince != null) {
            Text(
                text =
                    stringResource(
                        Res.string.discover_listening_as_of,
                        relativeLastActiveInSentence(staleSince, nowMs),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(horizontal = Spacing.screenMargin)
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(Spacing.md))
        when (val body = listeningNowBody(state, community)) {
            ListeningNowBody.Skeleton -> {
                RailSkeleton(
                    cardWidth = if (wide) null else LISTENER_CARD_WIDTH,
                    captionLines = 2,
                    modifier = Modifier.padding(horizontal = Spacing.screenMargin).testTag("listening-skeleton"),
                )
            }

            ListeningNowBody.Retry -> {
                InlineRetry(
                    line = stringResource(Res.string.discover_error_listening),
                    onRetry = onRetry,
                    modifier = Modifier.padding(horizontal = Spacing.screenMargin),
                )
            }

            ListeningNowBody.Invitation -> {
                OnlyYouInvitation(
                    viewer = viewer,
                    onInvite = onInvite.takeIf { viewer?.canInvite == true },
                    modifier = Modifier.padding(horizontal = Spacing.screenMargin),
                )
            }

            ListeningNowBody.NobodyListening -> {
                Text(
                    text = stringResource(Res.string.discover_nobody_listening),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.screenMargin),
                )
            }

            is ListeningNowBody.Rows -> {
                ListenerRows(
                    sessions = body.sessions,
                    wide = wide,
                    nowMs = nowMs,
                    onBookClick = onBookClick,
                    onUserClick = onUserClick,
                    isInSelectionMode = isInSelectionMode,
                    selectedBookIds = selectedBookIds,
                    onBookLongPress = onBookLongPress,
                )
            }
        }
    }
}

/** What Listening now draws under its heading: one decision, so every platform reads the same table. */
internal sealed interface ListeningNowBody {
    /** Loading, or empty before the roster has synced: never claim "nobody" or "just you" early (plan C4). */
    data object Skeleton : ListeningNowBody

    /** The section failed: its line and Try again. */
    data object Retry : ListeningNowBody

    /** Only you on the server: the invitation. */
    data object Invitation : ListeningNowBody

    /** Others on the server, but nobody to show. */
    data object NobodyListening : ListeningNowBody

    /** People to show. */
    data class Rows(
        val sessions: List<CurrentlyListeningUiSession>,
    ) : ListeningNowBody
}

/** Listening now's body for [state], given who else is on the server. */
internal fun listeningNowBody(
    state: CurrentlyListeningUiState,
    community: CommunitySize,
): ListeningNowBody =
    when (state) {
        CurrentlyListeningUiState.Loading -> {
            ListeningNowBody.Skeleton
        }

        is CurrentlyListeningUiState.Error -> {
            ListeningNowBody.Retry
        }

        is CurrentlyListeningUiState.Ready -> {
            if (!state.isEmpty) {
                ListeningNowBody.Rows(state.sessions)
            } else {
                when (community) {
                    CommunitySize.Unknown -> ListeningNowBody.Skeleton
                    CommunitySize.JustYou -> ListeningNowBody.Invitation
                    CommunitySize.Others -> ListeningNowBody.NobodyListening
                }
            }
        }
    }

@Composable
private fun ListenerRows(
    sessions: List<CurrentlyListeningUiSession>,
    wide: Boolean,
    nowMs: Long,
    onBookClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    isInSelectionMode: Boolean,
    selectedBookIds: Set<String>,
    onBookLongPress: ((String) -> Unit)?,
) {
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
            items = sessions,
            minCellWidth = LISTENER_MIN_CELL,
            key = { it.sessionId },
            modifier = Modifier.padding(horizontal = Spacing.screenMargin),
        ) { session, width -> card(session, width) }
    } else {
        BrowseCarousel(
            items = sessions,
            itemWidth = LISTENER_CARD_WIDTH,
            itemSpacing = Spacing.md,
            key = { it.sessionId },
        ) { card(it, LISTENER_CARD_WIDTH) }
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
    val bookLabel =
        if (session.isLive) {
            stringResource(Res.string.discover_listener_live_a11y, session.bookTitle, session.displayName)
        } else {
            stringResource(
                Res.string.discover_listener_recent_a11y,
                session.bookTitle,
                session.displayName,
                relativeLastActiveInSentence(session.lastActiveAt, nowMs),
            )
        }
    val selectLabel = stringResource(Res.string.selection_select)
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        BookCard(
            // The board draws no author under a listener's title: the person row is the second line.
            cover = session.toCoverModel().copy(author = null),
            onClick = { onBookClick(session.bookId) },
            // One label per card (spec §8.2): the book and who is on it, not the art's title, the
            // caption and the Live chip read one after another. The person row stays its own stop.
            modifier =
                Modifier.clearAndSetSemantics {
                    contentDescription = bookLabel
                    role = Role.Button
                    onClick {
                        onBookClick(session.bookId)
                        true
                    }
                    onBookLongPress?.let { longPress ->
                        onLongClick(selectLabel) {
                            longPress(session.bookId)
                            true
                        }
                    }
                    if (isInSelectionMode) selected = isSelected
                },
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
