@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ActivitySyncPayload
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val log = loggerFor<ActivityRecorder>()

/**
 * Records a social activity by upserting it through the syncable [ActivitySyncRepository], which
 * bumps the revision and emits the row on the revision-cursored data channel — so the feed pushes
 * live to connected clients (book-access-gated by the firehose) AND self-heals via catch-up if a
 * live event is missed. Replaces the former lossy `broadcastControl(ActivityChanged)` nudge.
 *
 * Best-effort: a failure is logged and swallowed so it never breaks the originating action —
 * recording that you finished a book must not be able to fail the finish itself.
 * [kotlin.coroutines.cancellation.CancellationException] is always re-raised (via
 * [runCatchingCancellable]) so structured concurrency stays honest.
 *
 * The write is de-nested: every call site invokes [record] from suspend context between
 * transactions (never inside a non-suspend `suspendTransaction` lambda — the calls are suspend),
 * so [ActivitySyncRepository.upsert] opening its own transaction is safe — the same posture
 * `StatsRecorder`'s sequential steps rely on.
 */
class ActivityRecorder(
    private val syncRepo: ActivitySyncRepository,
    private val clock: Clock = Clock.System,
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
            val now = clock.now().toEpochMilliseconds()
            // `revision` is a placeholder — upsert assigns the real one via nextRevision(). userId = null
            // means a GLOBAL emit; the firehose gate then filters each subscriber by book access.
            val payload =
                ActivitySyncPayload(
                    id = Uuid.random().toString(),
                    userId = userId,
                    type = type,
                    bookId = bookId,
                    isReread = isReread,
                    durationMs = durationMs,
                    milestoneValue = milestoneValue,
                    milestoneUnit = milestoneUnit,
                    shelfId = shelfId,
                    shelfName = shelfName,
                    occurredAt = occurredAt ?: now,
                    revision = 0L,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                )
            syncRepo.upsert(payload, clientOpId = null, userId = null)
        }.onFailure { log.warn(it) { "failed to record activity type=$type user=$userId" } }
    }
}
