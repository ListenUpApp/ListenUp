package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.BrowseCarousel
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.components.toCoverModel
import com.calypsan.listenup.client.features.library.AvatarOverlayData
import com.calypsan.listenup.client.features.library.BookCard
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverViewModel
import com.calypsan.listenup.client.util.relativeLastActive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.discover_listening_now
import listenup.composeapp.generated.resources.discover_what_others_are_listening_to

/**
 * Horizontal section showing what other people on this server are listening to.
 *
 * Two kinds of row, in one carousel. Anyone with a live session leads, marked "Listening now";
 * everyone else follows on the book they most recently played, marked with how long ago that was.
 * The fill is the point: with a handful of users nobody is live most of the time, and a section
 * that hides itself whenever presence is empty is indistinguishable from one that is broken.
 *
 * The server owns the split and the order (live by session start, then recent by last-played), and
 * the Room mirror preserves it — this composable renders the list as given.
 */
@Composable
fun CurrentlyListeningSection(
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isInSelectionMode: Boolean = false,
    selectedBookIds: Set<String> = emptySet(),
    onBookLongPress: ((String) -> Unit)? = null,
    viewModel: DiscoverViewModel = koinViewModel(),
) {
    val state by viewModel.currentlyListeningState.collectAsStateWithLifecycle()

    CurrentlyListeningSection(
        state = state,
        onBookClick = onBookClick,
        modifier = modifier,
        isInSelectionMode = isInSelectionMode,
        selectedBookIds = selectedBookIds,
        onBookLongPress = onBookLongPress,
    )
}

/**
 * Stateless half of [CurrentlyListeningSection] — the renderable unit, so the live/recent marker
 * can be asserted without a ViewModel.
 *
 * @param nowMs the instant relative times are measured against. Read once per composition (the
 *   `DevicesScreen` pattern) so a recomposition cannot make "3 days ago" flicker to "4 days ago"
 *   mid-scroll; passed explicitly by tests.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun CurrentlyListeningSection(
    state: CurrentlyListeningUiState,
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isInSelectionMode: Boolean = false,
    selectedBookIds: Set<String> = emptySet(),
    onBookLongPress: ((String) -> Unit)? = null,
    nowMs: Long = remember { Clock.System.now().toEpochMilliseconds() },
) {
    // Loading and Error render nothing, as do genuinely empty rosters — on a server where nobody
    // has ever pressed play there is nothing honest to show, and an invented row would be worse.
    val ready = state as? CurrentlyListeningUiState.Ready ?: return
    if (ready.isEmpty) return

    Column(modifier = modifier) {
        SectionTitle(
            title = stringResource(Res.string.discover_what_others_are_listening_to),
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        BrowseCarousel(items = ready.sessions, key = { it.sessionId }) { session ->
            BookCard(
                cover = session.toCoverModel(),
                onClick = { onBookClick(session.bookId) },
                subtitle = session.marker(nowMs),
                avatarOverlay = AvatarOverlayData(userId = session.userId),
                cardWidth = 140.dp,
                isInSelectionMode = isInSelectionMode,
                isSelected = session.bookId in selectedBookIds,
                onLongPress = onBookLongPress?.let { cb -> { cb(session.bookId) } },
            )
        }
    }
}

/**
 * The line under the card: "Listening now" for a live row, otherwise how long ago they last played.
 *
 * Delegates to the shared [relativeLastActive] the devices list already speaks, so the app has one
 * relative-time vocabulary rather than a second one invented here.
 */
@Composable
private fun CurrentlyListeningUiSession.marker(nowMs: Long): String =
    if (isLive) {
        stringResource(Res.string.discover_listening_now)
    } else {
        relativeLastActive(lastActiveAt, nowMs)
    }
