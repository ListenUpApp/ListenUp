package com.calypsan.listenup.server.matching.apply

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.services.MatchCoverColumns
import kotlinx.coroutines.CancellationException

private val log = loggerFor<MatchCoverFiles>()
private const val COVER_KEY_HASH_LENGTH = 12

/**
 * Fetches a matched cover through [imageStorage] (the `SafeCoverUrl` guard, size cap and type checks) and
 * stores it as `covers/<bookId>-<sha256[0..12]>.<ext>` (decision D1). Naming it by its content means it never
 * overwrites the book's current file, which a match receipt keeps so Undo can restore it; the orphan sweep
 * reaps whichever file nothing names any more. Returns null when the fetch or the image check fails.
 */
internal class MatchCoverFiles(
    private val imageStorage: ImageStorage,
    private val coverImageStore: CoverImageStore,
) : MatchCoverStore {
    override suspend fun store(
        bookId: String,
        url: String,
    ): MatchCoverColumns? {
        val bytes =
            when (val fetched = imageStorage.downloadBytes(url)) {
                is AppResult.Success -> fetched.data
                is AppResult.Failure -> return null.also { log.warn { "Match cover fetch refused: ${fetched.error.code}" } }
            }
        return try {
            val key = "$bookId-${hashBytesSha256(bytes).take(COVER_KEY_HASH_LENGTH)}"
            val stored = coverImageStore.store.store(key, bytes, "image/jpeg")
            managedMatchCover("covers/${stored.path.name}", stored.sha256)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Match cover store failed for $bookId" }
            null
        }
    }
}
