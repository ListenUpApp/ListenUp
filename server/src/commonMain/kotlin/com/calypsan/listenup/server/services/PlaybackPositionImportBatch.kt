package com.calypsan.listenup.server.services

import app.cash.sqldelight.TransactionWithReturn
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.min
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Chunked batch importer backing [PlaybackPositionRepository.recordAllForImport].
 * Prepares every incoming row in memory against batch-fetched existing positions, commits
 * chunked write transactions, then fires the de-nested completion/start cascade per row.
 */
internal class PlaybackPositionImportBatch(
    private val db: ListenUpDatabase,
    private val clock: Clock,
    private val cascade: PlaybackPositionCascade,
    private val findByBookIds: suspend (UserId, List<BookId>) -> List<PlaybackPositionSyncPayload>,
    private val upsertInOpenTransaction:
        TransactionWithReturn<*>.(PlaybackPositionSyncPayload, Boolean, String) -> Unit,
) {
    suspend fun recordAll(rows: List<ImportPositionWrite>) {
        if (rows.isEmpty()) return

        // PREPARE — batch-read existing positions per user, then resolve the wins-guard + id-reuse
        // in memory. The running view (seeded from the DB read, updated per prepared write) makes an
        // intra-batch second row for the same (user, book) see the first, matching read-your-writes.
        val existingByKey = HashMap<Pair<String, String>, PlaybackPositionSyncPayload?>()
        rows.groupBy { it.userId }.forEach { (userId, userRows) ->
            findByBookIds(UserId(userId), userRows.map { BookId(it.bookId) }.distinct())
                .forEach { existing -> existingByKey[userId to existing.bookId] = existing }
        }

        // Same two-clamp reasoning as recordPosition: ABS import rows carry historical timestamps
        // that are legitimately old (untouched by this clamp — only future-dating is capped), but a
        // corrupt/future-dated import row must not be able to poison a row the same way a bad device
        // clock would. One `now` reading for the whole batch — every prepared row is clamped against it.
        val now = clock.now().toEpochMilliseconds()

        val prepared = ArrayList<PreparedPositionWrite>(rows.size)
        for (row in rows) {
            val key = row.userId to row.bookId
            val existing = existingByKey[key]
            val clampedLastPlayedAt = min(row.lastPlayedAt, now + SKEW_TOLERANCE_MS)
            // Poisoned-row override — see recordPosition's Clamp #2 comment.
            val existingIsPoisoned = existing != null && existing.lastPlayedAt > now + SKEW_TOLERANCE_MS
            // lastPlayedAt-wins: a stale write is a no-op, exactly as recordPosition returns early.
            if (existing != null && !existingIsPoisoned && existing.lastPlayedAt >= clampedLastPlayedAt) continue

            val payload =
                PlaybackPositionSyncPayload(
                    id = existing?.id ?: Uuid.random().toString(),
                    bookId = row.bookId,
                    positionMs = row.positionMs,
                    lastPlayedAt = clampedLastPlayedAt,
                    finished = row.finished,
                    playbackSpeed = row.playbackSpeed,
                    currentChapterId = row.currentChapterId,
                    volumeBoostDb = row.volumeBoostDb,
                    measuredGainDb = row.measuredGainDb,
                    finishedAt = resolveFinishedAt(row.finishedAt, now),
                    // ImportPositionWrite carries no record of whether the listener chose their speed
                    // or boost, so these take the defaults rather than inventing values.
                    hasCustomSpeed = false,
                    hasCustomBoost = false,
                    revision = 0L,
                    updatedAt = 0L,
                    createdAt = 0L,
                    deletedAt = null,
                )
            prepared +=
                PreparedPositionWrite(
                    userId = row.userId,
                    payload = payload,
                    priorFinished = existing?.finished == true,
                    existedBefore = existing != null,
                    startedBookOccurredAt = row.startedBookOccurredAt,
                )
            existingByKey[key] = payload
        }

        // WRITE — one suspendTransaction per chunk; upsertInOpenTransaction per row inside it. The
        // suspend-only FirehoseSuppressed marker is read once here and threaded into every write.
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null
        for (chunk in prepared.chunked(PERSIST_CHUNK_SIZE)) {
            suspendTransaction<Unit>(db) {
                chunk.forEach { row ->
                    upsertInOpenTransaction(row.payload, suppressed, row.userId)
                }
            }
        }

        // HOOKS — the same de-nested, post-commit cascade recordPosition fires, per row, in order.
        // NO presence write here, unlike recordPosition's cascade: imported history must not
        // announce every imported reader as live on a book they last opened months ago.
        for (row in prepared) {
            val lastPlayedAt = row.payload.lastPlayedAt
            cascade.fire(
                PositionCascadeEvent(
                    userId = row.userId,
                    bookId = row.payload.bookId,
                    finished = row.payload.finished,
                    priorFinished = row.priorFinished,
                    existedBefore = row.existedBefore,
                    completedAtMs = row.payload.finishedAt ?: lastPlayedAt,
                    startedAtMs = null,
                    restartedAtMs = row.startedBookOccurredAt ?: lastPlayedAt,
                    updatePresence = false,
                ),
            )
        }
    }

    /**
     * A prepared import position write: the resolved [payload] plus the pre-write view the
     * post-commit hooks decide on ([priorFinished], [existedBefore]) and the imported start date
     * override. Captured in the PREPARE phase so the WRITE phase stays purely synchronous and the
     * HOOKS phase fires the exact cascade [PlaybackPositionRepository.recordPosition] would.
     */
    private data class PreparedPositionWrite(
        val userId: String,
        val payload: PlaybackPositionSyncPayload,
        val priorFinished: Boolean,
        val existedBefore: Boolean,
        val startedBookOccurredAt: Long?,
    )

    private companion object {
        /** Import rows per write transaction — one [suspendTransaction] commits a whole chunk. */
        const val PERSIST_CHUNK_SIZE = 200
    }
}
