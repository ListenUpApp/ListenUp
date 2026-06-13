package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId

/** Utility contract for local image download/cache operations. */
interface ImageDownloaderContract {
    suspend fun deleteCover(bookId: BookId): AppResult<Unit>

    suspend fun downloadCover(bookId: BookId): AppResult<Boolean>

    suspend fun downloadContributorImage(contributorId: String): AppResult<Boolean>

    suspend fun downloadContributorImages(contributorIds: List<String>): AppResult<List<String>>

    fun getContributorImagePath(contributorId: String): String?

    suspend fun downloadSeriesCover(seriesId: String): AppResult<Boolean>

    suspend fun downloadSeriesCovers(seriesIds: List<String>): AppResult<List<String>>

    suspend fun downloadUserAvatar(
        userId: String,
        forceRefresh: Boolean = false,
    ): AppResult<Boolean>

    fun getUserAvatarPath(userId: String): String?

    suspend fun deleteUserAvatar(userId: String): AppResult<Unit>
}

/** Utility contract for rebuilding local full-text-search tables. */
interface FtsPopulatorContract {
    suspend fun rebuildAll()

    /**
     * Rebuild the search index only when it is empty — the startup self-heal. A no-op when the
     * index is already populated, so it is cheap to call on every engine start.
     */
    suspend fun rebuildIfEmpty()
}
