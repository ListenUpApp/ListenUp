package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.BookRatingService
import com.calypsan.listenup.api.dto.BookRatingMutation
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.core.error.ClientValidationException
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.local.db.BookExternalRatingDao
import com.calypsan.listenup.client.data.local.db.BookExternalRatingEntity
import com.calypsan.listenup.client.data.local.db.BookRatingDao
import com.calypsan.listenup.client.data.local.db.BookRatingEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.model.SourceCalibration
import com.calypsan.listenup.client.domain.model.listenUpScore
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.domain.ListenerRatingLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * Listener ratings over Room, written through [OfflineEditor] on a coalescing channel: the local
 * row changes at once (the UI never waits on a socket) and one op per book waits for a connection.
 *
 * A rating's `candidateId` is the client-minted wire id (SERVER-SYNC-04), and there is exactly one
 * row per (book, listener). The server resolves that natural pair itself — an existing row's id
 * always wins, so a fresh candidate would never be rejected, only ignored. The reason to reuse is
 * local: the row keeps ONE stable wire id, the one the server's frames and tombstones name, so an
 * echo lands on this row instead of re-keying it under an id that never reached the server. So the
 * FIRST time a listener rates a book, [rate] mints a fresh [Uuid]; every following [rate] or [clear]
 * of the same pair reuses [BookRatingEntity.syncId] off the existing local row — [BookRatingDao.find]
 * returns a tombstoned row too, so a clear-then-re-rate keeps the same id rather than minting another.
 *
 * Outside ratings ([observeExternalForBook]) are a second surface on the same interface:
 * server-written rows mirrored by [externalRatingDao], read-only from the client, with no
 * [OfflineEditor] involvement — see [BookExternalRatingDao]'s KDoc.
 * [refreshExternal] is the one write path, and it is a direct online RPC through [ratingChannel],
 * not the outbox: there is nothing to queue offline, since only the server can reach an outside
 * catalog. The two meet in [observeCombinedScores], the ListenUp score, where this server's
 * listeners are one more source beside the outside catalogs.
 */
internal class BookRatingRepositoryImpl(
    private val dao: BookRatingDao,
    private val externalRatingDao: BookExternalRatingDao,
    private val offlineEditor: OfflineEditor,
    private val authSession: AuthSession,
    private val ratingChannel: RpcChannel<BookRatingService>,
) : BookRatingRepository {
    override fun observeForBook(bookId: String): Flow<List<ListenerRating>> =
        dao.observeForBook(bookId).map { rows -> rows.map { it.toDomain() } }

    override fun observeAverages(): Flow<Map<String, ListenerAverage>> =
        dao.observeAverages().map { rows ->
            rows.associate {
                it.bookId to
                    ListenerAverage(averageHalfStars = it.averageHalfStars, count = it.ratingCount)
            }
        }

    override suspend fun rate(
        bookId: String,
        halfStars: Int,
        note: String?,
    ): AppResult<Unit> {
        val normalized = ListenerRatingLimits.normalizeNote(note)
        if (halfStars !in ListenerRatingLimits.MIN_HALF_STARS..ListenerRatingLimits.MAX_HALF_STARS) {
            return AppResult.Failure(
                ErrorMapper.map(
                    ClientValidationException(
                        userMessage = "Choose a rating between one and five stars.",
                        field = "halfStars",
                    ),
                ),
            )
        }
        if ((normalized?.length ?: 0) > ListenerRatingLimits.NOTE_MAX_CHARS) {
            return AppResult.Failure(
                ErrorMapper.map(
                    ClientValidationException(
                        userMessage = "Notes can be up to ${ListenerRatingLimits.NOTE_MAX_CHARS} characters.",
                        field = "note",
                    ),
                ),
            )
        }
        val me =
            authSession.getUserId()
                ?: return AppResult.Failure(ErrorMapper.map(IllegalStateException("No signed-in user")))
        val existing = dao.find(bookId, me)
        val candidateId = existing?.syncId ?: Uuid.random().toString()
        val now = currentEpochMilliseconds()
        return offlineEditor.edit(
            OutboxChannels.BookRatings,
            entityId = bookId,
            patch =
                BookRatingMutation.Set(
                    bookId = bookId,
                    candidateId = candidateId,
                    halfStars = halfStars,
                    note = normalized,
                ),
            op = OpKind.Upsert,
            coalesce = true,
        ) {
            dao.upsert(
                BookRatingEntity(
                    bookId = bookId,
                    userId = me,
                    syncId = candidateId,
                    halfStars = halfStars,
                    note = normalized,
                    ratedAt = existing?.takeIf { it.deletedAt == null }?.ratedAt ?: now,
                    updatedAt = now,
                    revision = existing?.revision ?: 0,
                    deletedAt = null,
                ),
            )
        }
    }

    override suspend fun clear(bookId: String): AppResult<Unit> {
        val me =
            authSession.getUserId()
                ?: return AppResult.Failure(ErrorMapper.map(IllegalStateException("No signed-in user")))
        return offlineEditor.edit(
            OutboxChannels.BookRatings,
            entityId = bookId,
            patch = BookRatingMutation.Clear(bookId = bookId),
            op = OpKind.Upsert,
            coalesce = true,
        ) {
            dao.tombstone(bookId = bookId, userId = me, deletedAt = currentEpochMilliseconds())
        }
    }

    override fun observeExternalForBook(bookId: String): Flow<List<ExternalRating>> =
        externalRatingDao.observeEnabledForBook(bookId).map { rows -> rows.map { it.toExternalRating() } }

    /**
     * One pass per emission: calibrate every source's curve over the whole library (every enabled
     * known-source outside row, every book's listener average), then score each book against it.
     * Nothing here reads the signed-in user, so every member gets the same scores.
     */
    override fun observeCombinedScores(): Flow<Map<String, CombinedScore>> =
        combine(externalRatingDao.observeAllEnabled(), observeAverages()) { rows, listenerAverages ->
            val outsideByBook = rows.groupBy(keySelector = { it.bookId }, valueTransform = { it.toExternalRating() })
            val calibration =
                SourceCalibration.from(
                    outside = outsideByBook.values.flatten(),
                    listeners = listenerAverages.values.toList(),
                )
            (outsideByBook.keys + listenerAverages.keys)
                .mapNotNull { bookId ->
                    listenUpScore(
                        outside = outsideByBook[bookId].orEmpty(),
                        listeners = listenerAverages[bookId],
                        calibration = calibration,
                    )?.let { bookId to it }
                }.toMap()
        }

    override fun observeCombinedScore(bookId: String): Flow<CombinedScore?> =
        observeCombinedScores().map { it[bookId] }.distinctUntilChanged()

    override suspend fun refreshExternal(bookId: String): AppResult<Unit> =
        ratingChannel.call { it.refreshExternalRatings(BookId(bookId)) }

    override suspend fun ensureExternal(bookId: String): AppResult<Unit> =
        ratingChannel.call(idempotent = true) { it.ensureExternalRatings(BookId(bookId)) }
}

private fun BookRatingEntity.toDomain(): ListenerRating =
    ListenerRating(bookId = bookId, userId = userId, halfStars = halfStars, note = note, ratedAtMs = ratedAt)

/**
 * [BookExternalRatingEntity.source] is always a real [ExternalRatingSource] name, never a foreign
 * literal — the sync apply stores `payload.source.name`, and that `source` was already decoded
 * through the wire serializer's `UNKNOWN` fallback. [observeEnabledForBook] and [observeAllEnabled]
 * additionally filter `"UNKNOWN"` rows out before they ever reach here, so [valueOf] is safe.
 */
private fun BookExternalRatingEntity.toExternalRating(): ExternalRating =
    ExternalRating(source = ExternalRatingSource.valueOf(source), average = average, count = count)
