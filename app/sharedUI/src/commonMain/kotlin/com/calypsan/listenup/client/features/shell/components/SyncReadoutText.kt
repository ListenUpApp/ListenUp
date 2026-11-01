package com.calypsan.listenup.client.features.shell.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.calypsan.listenup.client.presentation.sync.SyncReadout
import kotlinx.coroutines.delay
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.shell_sync_could_not
import listenup.composeapp.generated.resources.shell_sync_days_ago
import listenup.composeapp.generated.resources.shell_sync_hours_ago
import listenup.composeapp.generated.resources.shell_sync_just_now
import listenup.composeapp.generated.resources.shell_sync_minutes_ago
import listenup.composeapp.generated.resources.shell_sync_not_yet
import listenup.composeapp.generated.resources.shell_syncing
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** The readout in words ("Synced 2 min ago"): the shared rule's value, in this platform's language. */
@Composable
internal fun syncReadoutText(readout: SyncReadout): String =
    when (readout) {
        SyncReadout.Syncing -> stringResource(Res.string.shell_syncing)
        SyncReadout.CouldNotSync -> stringResource(Res.string.shell_sync_could_not)
        SyncReadout.NotSyncedYet -> stringResource(Res.string.shell_sync_not_yet)
        SyncReadout.SyncedJustNow -> stringResource(Res.string.shell_sync_just_now)
        is SyncReadout.SyncedMinutesAgo -> stringResource(Res.string.shell_sync_minutes_ago, readout.count.toInt())
        is SyncReadout.SyncedHoursAgo -> stringResource(Res.string.shell_sync_hours_ago, readout.count.toInt())
        is SyncReadout.SyncedDaysAgo -> stringResource(Res.string.shell_sync_days_ago, readout.count.toInt())
    }

/** Now in epoch milliseconds, re-read every half minute so "2 min ago" keeps up without a tick per frame. */
@Composable
internal fun rememberMinuteClockMs(): State<Long> =
    produceState(initialValue = Clock.System.now().toEpochMilliseconds()) {
        while (true) {
            delay(CLOCK_STEP_MS)
            value = Clock.System.now().toEpochMilliseconds()
        }
    }

private const val CLOCK_STEP_MS = 30_000L
