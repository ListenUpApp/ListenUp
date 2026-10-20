package com.calypsan.listenup.web.features.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorUiEvent
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorUiState
import com.calypsan.listenup.client.presentation.sync.SyncIndicatorViewModel
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.nav.RouteSession
import com.calypsan.listenup.web.shell.nowMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.koin.core.Koin

/**
 * Whether the library is in step with the server, and the one thing to do about it: sync now (spec §3.1.7 L1/L33,
 * "Synced 2 min ago · Refresh now"). The phone header and the desktop sidebar both draw this one session.
 *
 * The failed half of the same ViewModel is [DeadLetterSession]'s; this is the other half, which the web did not show
 * until the phone header needed a sync control like Android's and iOS's.
 */
class SyncIndicatorSession(
    val state: StateFlow<SyncIndicatorUiState>,
    val onRefresh: () -> Unit,
    override val close: () -> Unit,
) : RouteSession

/** How the sync indicator gets its state. */
typealias OpenSyncIndicator = () -> SyncIndicatorSession

/** The production source: the shared [SyncIndicatorViewModel]. */
fun graphSyncIndicator(koin: Koin): OpenSyncIndicator =
    {
        val viewModel = koin.get<SyncIndicatorViewModel>()
        val store = ViewModelStore().apply { put("syncIndicator", viewModel) }
        SyncIndicatorSession(
            state = viewModel.state,
            onRefresh = { viewModel.onEvent(SyncIndicatorUiEvent.RefreshRequested) },
            close = { store.clear() },
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedSyncIndicator(
    state: SyncIndicatorUiState = SyncIndicatorUiState(),
    onRefresh: () -> Unit = {},
): OpenSyncIndicator = { SyncIndicatorSession(state = MutableStateFlow(state), onRefresh = onRefresh, close = {}) }

/** "Synced 2 min ago", "Syncing…", or "Not synced yet", measured against [now]. */
internal fun syncedWords(
    state: SyncIndicatorUiState,
    now: Long,
): String {
    if (state.isSyncing) return "Syncing…"
    val last = state.lastSyncedAtMs ?: return "Not synced yet"
    val minutes = (now - last).coerceAtLeast(0L) / MS_PER_MINUTE
    return when {
        minutes < 1 -> "Synced just now"
        minutes < MINUTES_PER_HOUR -> "Synced $minutes min ago"
        minutes < MINUTES_PER_DAY -> "Synced ${minutes / MINUTES_PER_HOUR} h ago"
        else -> "Synced ${minutes / MINUTES_PER_DAY} d ago"
    }
}

/** Now, re-read every half minute, so "2 min ago" keeps up with the clock without a tick per frame. */
@Composable
internal fun rememberMinuteClock(): Long {
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_STEP_MS)
            now = nowMs()
        }
    }
    return now
}

/**
 * The phone header's sync control: one 44px button, named for the state and what it does ("Synced 2 min ago.
 * Refresh"), that syncs now. Spins while a sync runs.
 */
@Composable
fun SyncIndicatorButton(
    state: SyncIndicatorUiState,
    now: Long,
    onRefresh: () -> Unit,
) {
    val words = syncedWords(state, now)
    Button(
        kind = ButtonKind.Icon,
        size = ButtonSize.Md,
        onClick = { onRefresh() },
        label = "$words. Refresh",
        attrs = {
            classes("ptb-b", "sync-ind-b")
            if (state.isSyncing) classes("is-syncing")
        },
    ) { Icon(WebIcon.Refresh, size = SYNC_ICON_SIZE) }
}

/** The desktop sidebar's sync line: "Synced 2 min ago · Refresh now". */
@Composable
fun SyncStatusLine(
    state: SyncIndicatorUiState,
    now: Long,
    onRefresh: () -> Unit,
) {
    Div(attrs = { classes("sync-ind") }) {
        Span(attrs = {
            classes("sync-ind-t")
            // A polite live region: a finished sync is news, but never worth interrupting for.
            attr("aria-live", "polite")
        }) { Text(syncedWords(state, now)) }
        Button(
            kind = ButtonKind.Ghost,
            size = ButtonSize.Sm,
            onClick = { onRefresh() },
            attrs = { classes("sync-ind-refresh") },
        ) { Text("Refresh now") }
    }
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 1_440L
private const val CLOCK_STEP_MS = 30_000L
private const val SYNC_ICON_SIZE = 20
