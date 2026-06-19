package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.PlaybackProgressService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.services.PlaybackPositionRepository

/**
 * [PlaybackProgressService] implementation. Resolves the authenticated caller
 * from [principal] (never from request fields — prevents spoofing), then
 * delegates to [PlaybackPositionRepository] for each query.
 *
 * Each `limit` parameter is clamped server-side to method-specific bounds
 * before reaching the repository, so out-of-range client values cannot force
 * unbounded scans or empty result pages.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated
 * [UserPrincipal] — the Koin singleton carries an unscoped placeholder that
 * throws on [PrincipalProvider.current] to catch misuse early.
 */
internal class PlaybackProgressServiceImpl(
    private val repository: PlaybackPositionRepository,
    private val principal: PrincipalProvider,
) : PlaybackProgressService {
    override suspend fun listProgress(limit: Int): AppResult<List<PlaybackPositionSyncPayload>> {
        val userId = resolveUser() ?: return noPrincipal()
        val clamped = limit.coerceIn(LIST_PROGRESS_MIN, LIST_PROGRESS_MAX)
        return AppResult.Success(repository.listForUser(userId, clamped))
    }

    override suspend fun getProgressBatch(bookIds: List<BookId>): AppResult<List<PlaybackPositionSyncPayload>> {
        val userId = resolveUser() ?: return noPrincipal()
        return AppResult.Success(repository.findByBookIds(userId, bookIds))
    }

    override suspend fun getRecentlyListened(limit: Int): AppResult<List<PlaybackPositionSyncPayload>> {
        val userId = resolveUser() ?: return noPrincipal()
        val clamped = limit.coerceIn(RECENT_MIN, RECENT_MAX)
        return AppResult.Success(repository.recentlyListenedForUser(userId, clamped))
    }

    override suspend fun getCompletedBooks(limit: Int): AppResult<List<PlaybackPositionSyncPayload>> {
        val userId = resolveUser() ?: return noPrincipal()
        val clamped = limit.coerceIn(COMPLETED_MIN, COMPLETED_MAX)
        return AppResult.Success(repository.completedForUser(userId, clamped))
    }

    private fun resolveUser(): UserId? = principal.current()?.userId

    private fun noPrincipal(): AppResult.Failure =
        AppResult.Failure(SyncError.NotFound(domain = "principal", entityId = "none"))

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): PlaybackProgressServiceImpl =
        PlaybackProgressServiceImpl(
            repository = repository,
            principal = principal,
        )

    private companion object {
        const val LIST_PROGRESS_MIN = 1
        const val LIST_PROGRESS_MAX = 500
        const val RECENT_MIN = 1
        const val RECENT_MAX = 100
        const val COMPLETED_MIN = 1
        const val COMPLETED_MAX = 500
    }
}
