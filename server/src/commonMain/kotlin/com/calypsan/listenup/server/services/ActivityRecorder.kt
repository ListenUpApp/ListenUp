package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.util.runCatchingCancellable
import com.calypsan.listenup.server.logging.loggerFor

private val log = loggerFor<ActivityRecorder>()

/**
 * Records a social activity and fires the broadcast [SyncControl.ActivityChanged] nudge so every
 * connected client re-pulls the feed. Best-effort: a failure is logged and swallowed so it never
 * breaks the originating action — recording that you finished a book must not be able to fail the
 * finish itself. [CancellationException] is always re-raised so structured concurrency stays honest.
 */
class ActivityRecorder(
    private val repo: ActivityRepository,
    private val bus: ChangeBus,
) {
    suspend fun record(
        userId: String,
        type: String,
        bookId: String? = null,
        isReread: Boolean = false,
        durationMs: Long = 0L,
        milestoneValue: Int = 0,
        milestoneUnit: String? = null,
        shelfId: String? = null,
        shelfName: String? = null,
        occurredAt: Long? = null,
    ) {
        runCatchingCancellable {
            repo.record(
                userId,
                type,
                bookId,
                isReread,
                durationMs,
                milestoneValue,
                milestoneUnit,
                shelfId,
                shelfName,
                occurredAt,
            )
            bus.broadcastControl(SyncControl.ActivityChanged)
        }.onFailure { log.warn(it) { "failed to record activity type=$type user=$userId" } }
    }
}
