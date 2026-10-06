@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.BookFindResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId

/**
 * Match details over [com.calypsan.listenup.api.MatchingService] — one method per RPC, `:contract` DTOs
 * exposed directly. Internal until a ViewModel brings Match details to the platforms, so the Swift surface is
 * untouched until then.
 */
internal interface MatchingRepository {
    /** Finds [bookId] in every catalogue and ranks the candidates against it. Retry is this same call. */
    suspend fun findBookMatches(
        bookId: BookId,
        request: BookFindRequest = BookFindRequest(),
    ): AppResult<BookFindResult>
}
