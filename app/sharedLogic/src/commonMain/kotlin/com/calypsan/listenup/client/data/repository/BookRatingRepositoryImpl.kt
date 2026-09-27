package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.BookRatingMutation
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.error.ClientValidationException
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.local.db.BookRatingDao
import com.calypsan.listenup.client.data.local.db.BookRatingEntity
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.domain.ListenerRatingLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * Listener ratings over Room, written through [OfflineEditor] on a coalescing channel: the local
 * row changes at once (the UI never waits on a socket) and one op per book waits for a connection.
 *
 * A rating's `candidateId` is the client-minted wire id (SERVER-SYNC-04): the server refuses a
 * candidate id that collides with another row, and there is exactly one row per (book, listener).
 * So the FIRST time a listener rates a book, [rate] mints a fresh [Uuid]; every following [rate] or
 * [clear] of the same pair reuses [BookRatingEntity.syncId] off the existing local row —
 * [BookRatingDao.find] returns a tombstoned row too, so a clear-then-re-rate reuses the same id
 * rather than minting a second one the server would reject.
 */
internal class BookRatingRepositoryImpl(
    private val dao: BookRatingDao,
    private val offlineEditor: OfflineEditor,
    private val authSession: AuthSession,
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
                        userMessage = "Choose a rating between half a star and five stars.",
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
}

private fun BookRatingEntity.toDomain(): ListenerRating =
    ListenerRating(bookId = bookId, userId = userId, halfStars = halfStars, note = note, ratedAtMs = ratedAt)
