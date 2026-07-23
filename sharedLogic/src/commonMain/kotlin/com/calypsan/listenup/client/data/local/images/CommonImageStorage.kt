package com.calypsan.listenup.client.data.local.images

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.domain.repository.ImageStorage
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write

/**
 * Shared implementation of [ImageStorage] using kotlinx-io.
 *
 * This implementation works across all platforms (Android, iOS, Desktop).
 * Platform-specific code only needs to provide [StoragePaths] with the base directory.
 *
 * Storage structure:
 * - {filesDir}/covers/{bookId}.jpg - Book cover images
 * - {filesDir}/covers/{bookId}_staging.jpg - Staging covers for edit preview
 * - {filesDir}/contributors/{contributorId}.jpg - Contributor profile images
 * - {filesDir}/contributors/{contributorId}_staging.jpg - Staging contributor images for edit preview
 * - {filesDir}/covers/series/{seriesId}.jpg - Series cover images
 * - {filesDir}/covers/series/{seriesId}_staging.jpg - Staging series covers
 * - {filesDir}/avatars/{userId}.jpg - User profile avatar images
 */
internal class CommonImageStorage(
    storagePaths: StoragePaths,
) : ImageStorage {
    private val filesDir: Path = storagePaths.filesDir
    private val coversDir: Path = Path(filesDir.toString(), COVERS_DIR_NAME)
    private val contributorsDir: Path = Path(filesDir.toString(), CONTRIBUTORS_DIR_NAME)
    private val seriesCoversDir: Path = Path(filesDir.toString(), SERIES_COVERS_DIR_NAME)
    private val avatarsDir: Path = Path(filesDir.toString(), AVATARS_DIR_NAME)

    // ========== Book Cover Methods ==========

    override suspend fun saveCover(
        bookId: BookId,
        imageData: ByteArray,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                val file = getCoverFile(bookId)
                writeBytes(file, imageData)
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to save cover for book ${bookId.value}", e))
            }
        }

    override fun getCoverPath(bookId: BookId): String = getCoverFile(bookId).toString()

    override fun exists(bookId: BookId): Boolean = SystemFileSystem.exists(getCoverFile(bookId))

    override fun listCoverBookIds(): Set<BookId> {
        if (!SystemFileSystem.exists(coversDir)) return emptySet()
        return SystemFileSystem
            .list(coversDir)
            .filter { it.name.endsWith(".$FILE_EXTENSION") && !it.name.endsWith("_staging.$FILE_EXTENSION") }
            .map { BookId(it.name.removeSuffix(".$FILE_EXTENSION")) }
            .toSet()
    }

    override suspend fun deleteCover(bookId: BookId): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                val file = getCoverFile(bookId)
                deleteIfExists(file)
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to delete cover for book ${bookId.value}", e))
            }
        }

    // ========== Book Cover Staging Methods ==========

    override suspend fun saveCoverStaging(
        bookId: BookId,
        imageData: ByteArray,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                val file = getCoverStagingFile(bookId)
                writeBytes(file, imageData)
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to save staging cover for book ${bookId.value}", e))
            }
        }

    override fun getCoverStagingPath(bookId: BookId): String = getCoverStagingFile(bookId).toString()

    override suspend fun commitCoverStaging(bookId: BookId): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                val stagingFile = getCoverStagingFile(bookId)
                val targetFile = getCoverFile(bookId)

                if (!SystemFileSystem.exists(stagingFile)) {
                    return@withContext Failure(
                        IOException("No staging cover to commit for book ${bookId.value}"),
                    )
                }

                // Read staging, write to target, delete staging
                val data = readBytes(stagingFile)
                writeBytes(targetFile, data)
                SystemFileSystem.delete(stagingFile)

                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to commit staging cover for book ${bookId.value}", e))
            }
        }

    override suspend fun deleteCoverStaging(bookId: BookId): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                deleteIfExists(getCoverStagingFile(bookId))
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to delete staging cover for book ${bookId.value}", e))
            }
        }

    override suspend fun clearAll(): AppResult<Int> =
        withContext(IODispatcher) {
            try {
                var deletedCount = 0

                // Clear covers
                deletedCount += clearDirectory(coversDir)
                // Clear contributor images
                deletedCount += clearDirectory(contributorsDir)
                // Clear series covers
                deletedCount += clearDirectory(seriesCoversDir)
                // Clear user avatars
                deletedCount += clearDirectory(avatarsDir)

                AppResult.Success(deletedCount)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to clear image cache", e))
            }
        }

    // ========== Contributor Image Methods ==========

    override suspend fun saveContributorImage(
        contributorId: String,
        imageData: ByteArray,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                val file = getContributorFile(contributorId)
                writeBytes(file, imageData)
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to save image for contributor $contributorId", e))
            }
        }

    override fun getContributorImagePath(contributorId: String): String = getContributorFile(contributorId).toString()

    override fun contributorImageExists(contributorId: String): Boolean =
        SystemFileSystem.exists(getContributorFile(contributorId))

    override suspend fun deleteContributorImage(contributorId: String): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                deleteIfExists(getContributorFile(contributorId))
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to delete image for contributor $contributorId", e))
            }
        }

    // ========== Contributor Image Staging Methods ==========

    override suspend fun saveContributorImageStaging(
        contributorId: String,
        imageData: ByteArray,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                val file = getContributorStagingFile(contributorId)
                writeBytes(file, imageData)
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to save staging image for contributor $contributorId", e))
            }
        }

    override fun getContributorImageStagingPath(contributorId: String): String =
        getContributorStagingFile(contributorId).toString()

    override suspend fun commitContributorImageStaging(contributorId: String): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                val stagingFile = getContributorStagingFile(contributorId)
                val targetFile = getContributorFile(contributorId)

                if (!SystemFileSystem.exists(stagingFile)) {
                    return@withContext Failure(
                        IOException("No staging image to commit for contributor $contributorId"),
                    )
                }

                // Read staging, write to target, delete staging
                val data = readBytes(stagingFile)
                writeBytes(targetFile, data)
                SystemFileSystem.delete(stagingFile)

                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to commit staging image for contributor $contributorId", e))
            }
        }

    override suspend fun deleteContributorImageStaging(contributorId: String): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                deleteIfExists(getContributorStagingFile(contributorId))
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to delete staging image for contributor $contributorId", e))
            }
        }

    // ========== Series Cover Methods ==========

    override suspend fun saveSeriesCover(
        seriesId: String,
        imageData: ByteArray,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                val file = getSeriesCoverFile(seriesId)
                writeBytes(file, imageData)
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to save cover for series $seriesId", e))
            }
        }

    override fun getSeriesCoverPath(seriesId: String): String = getSeriesCoverFile(seriesId).toString()

    override fun seriesCoverExists(seriesId: String): Boolean = SystemFileSystem.exists(getSeriesCoverFile(seriesId))

    override suspend fun deleteSeriesCover(seriesId: String): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                deleteIfExists(getSeriesCoverFile(seriesId))
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to delete cover for series $seriesId", e))
            }
        }

    // ========== Series Cover Staging Methods ==========

    override suspend fun saveSeriesCoverStaging(
        seriesId: String,
        imageData: ByteArray,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                val file = getSeriesCoverStagingFile(seriesId)
                writeBytes(file, imageData)
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to save staging cover for series $seriesId", e))
            }
        }

    override fun getSeriesCoverStagingPath(seriesId: String): String = getSeriesCoverStagingFile(seriesId).toString()

    override suspend fun commitSeriesCoverStaging(seriesId: String): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                val stagingFile = getSeriesCoverStagingFile(seriesId)
                val targetFile = getSeriesCoverFile(seriesId)

                if (!SystemFileSystem.exists(stagingFile)) {
                    return@withContext Failure(
                        IOException("No staging cover to commit for series $seriesId"),
                    )
                }

                // Read staging, write to target, delete staging
                val data = readBytes(stagingFile)
                writeBytes(targetFile, data)
                SystemFileSystem.delete(stagingFile)

                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to commit staging cover for series $seriesId", e))
            }
        }

    override suspend fun deleteSeriesCoverStaging(seriesId: String): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                deleteIfExists(getSeriesCoverStagingFile(seriesId))
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to delete staging cover for series $seriesId", e))
            }
        }

    // ========== User Avatar Methods ==========

    override suspend fun saveUserAvatar(
        userId: String,
        imageData: ByteArray,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                val file = getUserAvatarFile(userId)
                writeBytes(file, imageData)
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to save avatar for user $userId", e))
            }
        }

    override fun getUserAvatarPath(userId: String): String = getUserAvatarFile(userId).toString()

    override fun userAvatarExists(userId: String): Boolean = SystemFileSystem.exists(getUserAvatarFile(userId))

    override suspend fun deleteUserAvatar(userId: String): AppResult<Unit> =
        withContext(IODispatcher) {
            try {
                deleteIfExists(getUserAvatarFile(userId))
                AppResult.Success(Unit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Failure(IOException("Failed to delete avatar for user $userId", e))
            }
        }

    // ========== Private Helpers ==========

    private fun getCoverFile(bookId: BookId): Path = Path(coversDir.toString(), "${bookId.value}.$FILE_EXTENSION")

    private fun getCoverStagingFile(bookId: BookId): Path =
        Path(coversDir.toString(), "${bookId.value}_staging.$FILE_EXTENSION")

    private fun getContributorFile(contributorId: String): Path =
        Path(contributorsDir.toString(), "$contributorId.$FILE_EXTENSION")

    private fun getContributorStagingFile(contributorId: String): Path =
        Path(contributorsDir.toString(), "${contributorId}_staging.$FILE_EXTENSION")

    private fun getSeriesCoverFile(seriesId: String): Path =
        Path(seriesCoversDir.toString(), "$seriesId.$FILE_EXTENSION")

    private fun getSeriesCoverStagingFile(seriesId: String): Path =
        Path(seriesCoversDir.toString(), "${seriesId}_staging.$FILE_EXTENSION")

    private fun getUserAvatarFile(userId: String): Path = Path(avatarsDir.toString(), "$userId.$FILE_EXTENSION")

    /**
     * Write bytes to a file, creating parent directories if needed.
     */
    private fun writeBytes(
        path: Path,
        data: ByteArray,
    ) {
        // Ensure parent directory exists
        val parent = path.parent
        if (parent != null && !SystemFileSystem.exists(parent)) {
            SystemFileSystem.createDirectories(parent)
        }
        SystemFileSystem.sink(path).buffered().use { sink ->
            sink.write(data)
        }
    }

    /**
     * Read all bytes from a file.
     */
    private fun readBytes(path: Path): ByteArray =
        SystemFileSystem.source(path).buffered().use { source ->
            source.readByteArray()
        }

    /**
     * Delete a file if it exists.
     */
    private fun deleteIfExists(path: Path) {
        if (SystemFileSystem.exists(path)) {
            SystemFileSystem.delete(path)
        }
    }

    /**
     * Delete all .jpg files in a directory.
     */
    private fun clearDirectory(dir: Path): Int {
        if (!SystemFileSystem.exists(dir)) return 0

        var count = 0
        SystemFileSystem.list(dir).forEach { path ->
            if (path.name.endsWith(".$FILE_EXTENSION")) {
                SystemFileSystem.delete(path)
                count++
            }
        }
        return count
    }

    companion object {
        private const val COVERS_DIR_NAME = "covers"
        private const val CONTRIBUTORS_DIR_NAME = "contributors"
        private const val SERIES_COVERS_DIR_NAME = "covers/series"
        private const val AVATARS_DIR_NAME = "avatars"
        private const val FILE_EXTENSION = "jpg"
    }
}
