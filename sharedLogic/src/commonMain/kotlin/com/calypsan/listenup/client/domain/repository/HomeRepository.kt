package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import kotlinx.coroutines.flow.Flow

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
     * Observe continue listening books from local database.
     *
     * Provides real-time updates when playback positions change locally.
     * This is local-first: changes appear immediately without waiting for sync.
     *
     * @param limit Maximum number of books to return
     * @return Flow emitting list of ContinueListeningBook whenever positions change
     */
    fun observeContinueListening(limit: Int = 10): Flow<List<ContinueListeningBook>>
}
