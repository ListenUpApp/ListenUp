package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.activity.ActivityEvent
import com.calypsan.listenup.api.result.AppResult
import kotlinx.rpc.annotations.Rpc

/**
 * The cross-user social activity feed. Every page is filtered through `BookAccessPolicy` for the
 * calling viewer — activity about a book the caller cannot access is omitted; non-book activity
 * (shelf/milestone/user-joined) always passes.
 */
@Rpc
interface ActivityService {
    /** Most-recent-first page; [before] is the `createdAtMs` cursor (exclusive); null = head. */
    suspend fun feed(
        before: Long? = null,
        limit: Int = 20,
    ): AppResult<List<ActivityEvent>>
}
