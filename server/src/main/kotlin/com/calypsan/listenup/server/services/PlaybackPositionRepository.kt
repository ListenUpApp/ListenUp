@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.core.PlaybackPositionId
import com.calypsan.listenup.server.db.PlaybackPositionTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Syncable-domain repository for per-user playback positions (Playback P1).
 *
 * One row per `(userId, bookId)` pair — the current resume point for one user's
 * progress through one book. `lastPlayedAt`-wins conflict resolution: a write
 * with a stale `lastPlayedAt` (less than the stored value) is silently dropped
 * so a stale offline write never clobbers a fresher position from another device.
 *
 * `userScoped = true` — every `upsert`, `softDelete`, `pullSince`, and `digest`
 * call routes through the per-user dimension of the substrate.
 *
 * `idAsString(PlaybackPositionId) = id.value` is load-bearing — Kotlin's default
 * `toString()` on a value class returns `"PlaybackPositionId(value=foo)"`, which
 * would corrupt every column the id is written to.
 */
class PlaybackPositionRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
    private val userStatsUpdater: UserStatsUpdater? = null,
    private val activeSessionRepo: ActiveSessionRepository? = null,
) : SyncableRepository<PlaybackPositionSyncPayload, PlaybackPositionId>(
        db = db,
        table = PlaybackPositionTable,
        bus = bus,
        registry = registry,
        domainName = "playback_positions",
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override val elementSerializer: KSerializer<PlaybackPositionSyncPayload> =
        PlaybackPositionSyncPayload.serializer()

    override fun idAsString(id: PlaybackPositionId): String = id.value

    override val PlaybackPositionSyncPayload.id: PlaybackPositionId
        get() = PlaybackPositionId(this.id)

    override fun PlaybackPositionSyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): PlaybackPositionSyncPayload? =
        PlaybackPositionTable
            .selectAll()
            .where { PlaybackPositionTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                PlaybackPositionSyncPayload(
                    id = row[PlaybackPositionTable.id],
                    bookId = row[PlaybackPositionTable.bookId],
                    positionMs = row[PlaybackPositionTable.positionMs],
                    lastPlayedAt = row[PlaybackPositionTable.lastPlayedAt],
                    finished = row[PlaybackPositionTable.finished],
                    playbackSpeed = row[PlaybackPositionTable.playbackSpeed],
                    currentChapterId = row[PlaybackPositionTable.currentChapterId],
                    revision = row[PlaybackPositionTable.revision],
                    updatedAt = row[PlaybackPositionTable.updatedAt],
                    createdAt = row[PlaybackPositionTable.createdAt],
                    deletedAt = row[PlaybackPositionTable.deletedAt],
                )
            }

    override suspend fun writePayload(
        value: PlaybackPositionSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        requireNotNull(userId) { "PlaybackPositionRepository.writePayload requires a userId" }
        if (existed) {
            PlaybackPositionTable.update({ PlaybackPositionTable.id eq value.id }) { stmt ->
                stmt[PlaybackPositionTable.positionMs] = value.positionMs
                stmt[PlaybackPositionTable.lastPlayedAt] = value.lastPlayedAt
                stmt[PlaybackPositionTable.finished] = value.finished
                stmt[PlaybackPositionTable.playbackSpeed] = value.playbackSpeed
                stmt[PlaybackPositionTable.currentChapterId] = value.currentChapterId
                stmt[PlaybackPositionTable.revision] = rev
                stmt[PlaybackPositionTable.updatedAt] = now
                stmt[PlaybackPositionTable.deletedAt] = null
                stmt[PlaybackPositionTable.clientOpId] = clientOpId
            }
        } else {
            PlaybackPositionTable.insert { stmt ->
                stmt[PlaybackPositionTable.id] = value.id
                stmt[PlaybackPositionTable.userId] = userId
                stmt[PlaybackPositionTable.bookId] = value.bookId
                stmt[PlaybackPositionTable.positionMs] = value.positionMs
                stmt[PlaybackPositionTable.lastPlayedAt] = value.lastPlayedAt
                stmt[PlaybackPositionTable.finished] = value.finished
                stmt[PlaybackPositionTable.playbackSpeed] = value.playbackSpeed
                stmt[PlaybackPositionTable.currentChapterId] = value.currentChapterId
                stmt[PlaybackPositionTable.revision] = rev
                stmt[PlaybackPositionTable.createdAt] = now
                stmt[PlaybackPositionTable.updatedAt] = now
                stmt[PlaybackPositionTable.deletedAt] = null
                stmt[PlaybackPositionTable.clientOpId] = clientOpId
            }
        }
    }

    /**
     * Record a playback position for `(userId, bookId)`. `lastPlayedAt`-wins: if
     * a row already exists with a `lastPlayedAt >= the incoming one, this is a
     * no-op and the stored payload is returned unchanged — a stale offline write
     * never clobbers a fresher position from another device.
     */
    suspend fun recordPosition(
        userId: String,
        bookId: String,
        positionMs: Long,
        lastPlayedAt: Long,
        finished: Boolean,
        playbackSpeed: Float,
        currentChapterId: String?,
    ): AppResult<PlaybackPositionSyncPayload> =
        suspendTransaction(db) {
            val existing = getPosition(userId, bookId)
            if (existing != null && existing.lastPlayedAt >= lastPlayedAt) {
                return@suspendTransaction AppResult.Success(existing)
            }

            val priorFinished = existing?.finished ?: false
            val id = existing?.id ?: Uuid.random().toString()
            val payload =
                PlaybackPositionSyncPayload(
                    id = id,
                    bookId = bookId,
                    positionMs = positionMs,
                    lastPlayedAt = lastPlayedAt,
                    finished = finished,
                    playbackSpeed = playbackSpeed,
                    currentChapterId = currentChapterId,
                    revision = 0L,
                    updatedAt = 0L,
                    createdAt = 0L,
                    deletedAt = null,
                )
            val result = upsert(payload, clientOpId = null, userId = userId)
            // Fire the finished flip when false → true. The caller is responsible for
            // detecting the flip condition; the updater unconditionally increments booksFinished.
            if (result is AppResult.Success && finished && !priorFinished) {
                userStatsUpdater?.onPositionFinishedFlip(userId)
                activeSessionRepo?.deleteForUserBook(userId, bookId)
            }
            result
        }

    /**
     * Returns the current position for `(userId, bookId)`, or `null` if the user
     * has never played this book.
     */
    suspend fun getPosition(
        userId: String,
        bookId: String,
    ): PlaybackPositionSyncPayload? =
        suspendTransaction(db) {
            PlaybackPositionTable
                .selectAll()
                .where {
                    (PlaybackPositionTable.userId eq userId) and
                        (PlaybackPositionTable.bookId eq bookId) and
                        (PlaybackPositionTable.deletedAt eq null)
                }.firstOrNull()
                ?.let { row ->
                    PlaybackPositionSyncPayload(
                        id = row[PlaybackPositionTable.id],
                        bookId = row[PlaybackPositionTable.bookId],
                        positionMs = row[PlaybackPositionTable.positionMs],
                        lastPlayedAt = row[PlaybackPositionTable.lastPlayedAt],
                        finished = row[PlaybackPositionTable.finished],
                        playbackSpeed = row[PlaybackPositionTable.playbackSpeed],
                        currentChapterId = row[PlaybackPositionTable.currentChapterId],
                        revision = row[PlaybackPositionTable.revision],
                        updatedAt = row[PlaybackPositionTable.updatedAt],
                        createdAt = row[PlaybackPositionTable.createdAt],
                        deletedAt = row[PlaybackPositionTable.deletedAt],
                    )
                }
        }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: PlaybackPositionId): String = idAsString(id)
}
