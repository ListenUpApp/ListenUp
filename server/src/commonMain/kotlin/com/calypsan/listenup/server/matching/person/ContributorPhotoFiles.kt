package com.calypsan.listenup.server.matching.person

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

private val log = loggerFor<ContributorPhotoFiles>()

/**
 * Fetches a contributor photo and stores it under `contributors/`, named by its content (`contributors/<sha>.<ext>`):
 * a new photo never overwrites the old one, so a match receipt can keep the old one for Undo and clients key their
 * image cache on the path. Every photo passes the store's magic-number sniff and size cap before it reaches disk.
 */
internal class ContributorPhotoFiles(
    private val imageStorage: ImageStorage,
    imageHome: Path,
    maxBytes: Long = CONTRIBUTOR_PHOTO_MAX_BYTES,
) {
    private val store = ImageStore(Path(imageHome.toString(), "contributors"), maxBytes)

    /** The stored photo's relative path (`contributors/<sha>.<ext>`), or null when the fetch or the image check fails. */
    suspend fun store(url: String): String? {
        val bytes =
            when (val fetched = imageStorage.downloadBytes(url)) {
                is AppResult.Success -> {
                    fetched.data
                }

                is AppResult.Failure -> {
                    log.warn { "Contributor photo fetch refused: ${fetched.error.code}" }
                    return null
                }
            }
        return try {
            // The declared type only colours a rejection message; the sniff decides.
            val stored = store.store(key = hashBytesSha256(bytes), bytes = bytes, declaredContentType = "image/*")
            "contributors/${stored.path.name}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageStore.InvalidImageException) {
            log.warn(e) { "Contributor photo rejected" }
            null
        } catch (e: Exception) {
            log.warn(e) { "Contributor photo write failed" }
            null
        }
    }

    companion object {
        /**
         * Ceiling for a *stored* contributor photo. Deliberately below the fetch ceiling in
         * [com.calypsan.listenup.server.metadata.BoundedImageFetch] so it is a real second gate rather than a
         * restatement of the first — a portrait is not a cover, and this matches what a user's own avatar upload
         * is allowed to be.
         */
        const val CONTRIBUTOR_PHOTO_MAX_BYTES: Long = 5L * 1024 * 1024
    }
}
