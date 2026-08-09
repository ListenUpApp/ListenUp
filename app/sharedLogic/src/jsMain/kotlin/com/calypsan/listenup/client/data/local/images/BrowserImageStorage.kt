package com.calypsan.listenup.client.data.local.images

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.core.BookId
import kotlinx.io.IOException

/**
 * Browser image storage: deliberately no byte persistence.
 *
 * On web, covers and avatars are exactly what the server's blob endpoints exist for — cacheable
 * HTTP GETs the browser's own cache handles better than any hand-rolled store. Persisting the
 * bytes again in OPFS would duplicate that cache without adding offline capability the web
 * client actually has (there is no offline playback on this platform yet either).
 *
 * What this keeps is the *bookkeeping*: saves record the id in memory so [exists] stays coherent
 * for the session and the sync path cannot loop on "saved it, still missing". The recorded set
 * does not survive a reload — after one, images simply load from the server again, which is the
 * web's normal behaviour, not a failure.
 *
 * Paths are virtual (`browser://…`): nothing on this platform loads images from file paths.
 *
 * Public rather than internal so `:app:webApp`'s browser specs can exercise it directly —
 * jsMain never reaches the iOS export surface, so visibility costs nothing.
 */
@Suppress("TooManyFunctions")
class BrowserImageStorage : ImageStorage {
    private val covers = mutableSetOf<String>()
    private val coverStaging = mutableSetOf<String>()
    private val contributors = mutableSetOf<String>()
    private val contributorStaging = mutableSetOf<String>()
    private val series = mutableSetOf<String>()
    private val seriesStaging = mutableSetOf<String>()
    private val avatars = mutableSetOf<String>()

    override suspend fun saveCover(
        bookId: BookId,
        imageData: ByteArray,
    ): AppResult<Unit> = record(covers, bookId.value)

    override fun getCoverPath(bookId: BookId): String = virtualPath("covers", bookId.value)

    override fun exists(bookId: BookId): Boolean = bookId.value in covers

    override fun listCoverBookIds(): Set<BookId> = covers.map { BookId(it) }.toSet()

    override suspend fun deleteCover(bookId: BookId): AppResult<Unit> = forget(covers, bookId.value)

    override suspend fun saveCoverStaging(
        bookId: BookId,
        imageData: ByteArray,
    ): AppResult<Unit> = record(coverStaging, bookId.value)

    override fun getCoverStagingPath(bookId: BookId): String = virtualPath("covers/staging", bookId.value)

    override suspend fun commitCoverStaging(bookId: BookId): AppResult<Unit> =
        commit(coverStaging, covers, bookId.value, "cover")

    override suspend fun deleteCoverStaging(bookId: BookId): AppResult<Unit> = forget(coverStaging, bookId.value)

    override suspend fun clearAll(): AppResult<Int> {
        val cleared = covers.size
        covers.clear()
        coverStaging.clear()
        return AppResult.Success(cleared)
    }

    override suspend fun saveContributorImage(
        contributorId: String,
        imageData: ByteArray,
    ): AppResult<Unit> = record(contributors, contributorId)

    override fun getContributorImagePath(contributorId: String): String = virtualPath("contributors", contributorId)

    override fun contributorImageExists(contributorId: String): Boolean = contributorId in contributors

    override suspend fun deleteContributorImage(contributorId: String): AppResult<Unit> =
        forget(contributors, contributorId)

    override suspend fun saveSeriesCover(
        seriesId: String,
        imageData: ByteArray,
    ): AppResult<Unit> = record(series, seriesId)

    override fun getSeriesCoverPath(seriesId: String): String = virtualPath("covers/series", seriesId)

    override fun seriesCoverExists(seriesId: String): Boolean = seriesId in series

    override suspend fun deleteSeriesCover(seriesId: String): AppResult<Unit> = forget(series, seriesId)

    override suspend fun saveUserAvatar(
        userId: String,
        imageData: ByteArray,
    ): AppResult<Unit> = record(avatars, userId)

    override fun getUserAvatarPath(userId: String): String = virtualPath("avatars", userId)

    override fun userAvatarExists(userId: String): Boolean = userId in avatars

    override suspend fun deleteUserAvatar(userId: String): AppResult<Unit> = forget(avatars, userId)

    override suspend fun saveSeriesCoverStaging(
        seriesId: String,
        imageData: ByteArray,
    ): AppResult<Unit> = record(seriesStaging, seriesId)

    override fun getSeriesCoverStagingPath(seriesId: String): String = virtualPath("covers/series/staging", seriesId)

    override suspend fun commitSeriesCoverStaging(seriesId: String): AppResult<Unit> =
        commit(seriesStaging, series, seriesId, "series cover")

    override suspend fun deleteSeriesCoverStaging(seriesId: String): AppResult<Unit> = forget(seriesStaging, seriesId)

    override suspend fun saveContributorImageStaging(
        contributorId: String,
        imageData: ByteArray,
    ): AppResult<Unit> = record(contributorStaging, contributorId)

    override fun getContributorImageStagingPath(contributorId: String): String =
        virtualPath("contributors/staging", contributorId)

    override suspend fun commitContributorImageStaging(contributorId: String): AppResult<Unit> =
        commit(contributorStaging, contributors, contributorId, "contributor image")

    override suspend fun deleteContributorImageStaging(contributorId: String): AppResult<Unit> =
        forget(contributorStaging, contributorId)

    private fun record(
        set: MutableSet<String>,
        id: String,
    ): AppResult<Unit> {
        set.add(id)
        return AppResult.Success(Unit)
    }

    private fun forget(
        set: MutableSet<String>,
        id: String,
    ): AppResult<Unit> {
        set.remove(id)
        return AppResult.Success(Unit)
    }

    /** Same contract as the filesystem implementations: committing nothing is an error. */
    private fun commit(
        staging: MutableSet<String>,
        target: MutableSet<String>,
        id: String,
        what: String,
    ): AppResult<Unit> {
        if (!staging.remove(id)) {
            return Failure(IOException("No staging $what to commit for $id"))
        }
        target.add(id)
        return AppResult.Success(Unit)
    }

    private fun virtualPath(
        category: String,
        id: String,
    ): String = "browser://images/$category/$id"
}
