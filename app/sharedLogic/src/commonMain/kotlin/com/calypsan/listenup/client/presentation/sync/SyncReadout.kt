package com.calypsan.listenup.client.presentation.sync

/**
 * What the sync readout says ("Synced 2 min ago"), as a value every platform words in its own language. The rule is
 * shared; the English is not.
 */
sealed interface SyncReadout {
    /** A sync is running. */
    data object Syncing : SyncReadout

    /** The last "sync now" failed; said until the next one so a good earlier sync never covers for it. */
    data object CouldNotSync : SyncReadout

    /** Nothing has synced yet this session. */
    data object NotSyncedYet : SyncReadout

    /** Under a minute ago. */
    data object SyncedJustNow : SyncReadout

    /** [count] whole minutes ago, under an hour. */
    data class SyncedMinutesAgo(
        val count: Long,
    ) : SyncReadout

    /** [count] whole hours ago, under a day. */
    data class SyncedHoursAgo(
        val count: Long,
    ) : SyncReadout

    /** [count] whole days ago. */
    data class SyncedDaysAgo(
        val count: Long,
    ) : SyncReadout
}

/** What [state] says about syncing, measured against [nowMs] (epoch milliseconds). */
fun syncReadoutOf(
    state: SyncIndicatorUiState,
    nowMs: Long,
): SyncReadout {
    if (state.isSyncing) return SyncReadout.Syncing
    if (state.refreshFailed) return SyncReadout.CouldNotSync
    val last = state.lastSyncedAtMs ?: return SyncReadout.NotSyncedYet
    val minutes = (nowMs - last).coerceAtLeast(0L) / MS_PER_MINUTE
    return when {
        minutes < 1 -> SyncReadout.SyncedJustNow
        minutes < MINUTES_PER_HOUR -> SyncReadout.SyncedMinutesAgo(minutes)
        minutes < MINUTES_PER_DAY -> SyncReadout.SyncedHoursAgo(minutes / MINUTES_PER_HOUR)
        else -> SyncReadout.SyncedDaysAgo(minutes / MINUTES_PER_DAY)
    }
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 1_440L
