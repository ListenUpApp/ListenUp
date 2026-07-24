@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.valueOrNull
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow

private val homeRepositoryLogger = KotlinLogging.logger {}

/**
 * Repository contract for home screen data operations.
 *
 * Handles fetching continue listening books and user data for the greeting.
 * Uses local-first approach for instant updates.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface HomeRepository {
    /**
     * Fetch books the user is currently listening to.
     *
     * @param limit Maximum number of books to return
     * @return Result containing list of ContinueListeningBook on success
     */
    suspend fun getContinueListening(limit: Int = 10): AppResult<List<ContinueListeningBook>>

    /**
     * iOS-safe accessor: the most-recent "continue listening" book, or `null`. Use from Swift —
     * never `await` the `AppResult`-returning [getContinueListening] (Swift Export bridge trap).
     */
    suspend fun getResumeBookOrNull(): ContinueListeningBook? =
        getContinueListening(limit = 5)
            .valueOrNull { homeRepositoryLogger.warn { "getResumeBookOrNull: ${it.debugInfo ?: it.message}" } }
            ?.firstOrNull()

    /**
     * Observe continue listening books from local database.
     *
     * Provides real-time updates when playback positions change locally.
     * This is local-first: changes appear immediately without waiting for sync.
     *
     * Items are [ContinueListeningItem.Ready] when the book is hydrated, or
     * [ContinueListeningItem.Loading] when the position row arrived before the
     * corresponding book has synced into Room (brief sync-window placeholder).
     *
     * @param limit Maximum number of books to return
     * @return Flow emitting list of [ContinueListeningItem] whenever positions change
     */
    fun observeContinueListening(limit: Int = 10): Flow<List<ContinueListeningItem>>
}
