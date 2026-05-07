package com.calypsan.listenup.client.data.sync.pull

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.remote.GenreApiContract
import com.calypsan.listenup.client.data.sync.model.SyncPhase
import com.calypsan.listenup.client.data.sync.model.SyncStatus
import com.calypsan.listenup.client.domain.model.Genre
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Handles syncing global genres from server.
 *
 * Genres are system-controlled categories that form a hierarchy.
 * Only syncs the global genre list. Book-genre relationships are synced inline
 * with books via BookPuller when the server includes genres in BookResponse.
 */
class GenrePuller(
    private val genreApi: GenreApiContract,
    private val genreDao: GenreDao,
) : Puller {
    /**
     * Pull global genres from server.
     *
     * Non-critical — failures are logged and [AppResult.Success] is still returned
     * so the surrounding sync cycle is not aborted.
     *
     * @param updatedAfter ISO timestamp for delta sync (currently ignored - full sync only)
     * @param onProgress Callback for progress updates
     */
    override suspend fun pull(
        updatedAfter: String?,
        onProgress: (SyncStatus) -> Unit,
    ): AppResult<Unit> {
        logger.debug { "Starting genre sync..." }

        onProgress(
            SyncStatus.Progress(
                phase = SyncPhase.SYNCING_GENRES,
                phaseItemsSynced = 0,
                phaseTotalItems = -1,
                message = "Syncing genres...",
            ),
        )

        when (val result = genreApi.listGenres()) {
            is AppResult.Failure -> {
                logger.warn { "Failed to fetch global genres: ${result.error.message}" }
                // Don't propagate — genres are not critical for sync
            }
            is AppResult.Success -> {
                val genres = result.data
                logger.info { "Fetched ${genres.size} global genres" }

                // Build path-to-ID lookup for resolving parent genres
                val pathToId = genres.associate { it.path to it.id }

                // Convert to entities and upsert
                val genreEntities = genres.map { it.toEntity(pathToId) }
                genreDao.upsertAll(genreEntities)
                logger.info { "Genre sync complete: ${genreEntities.size} genres synced" }
            }
        }

        return AppResult.Success(Unit)
    }

    /**
     * Convert domain Genre to GenreEntity.
     *
     * Computes parentId, depth, and sortOrder from the path.
     * Uses pathToId lookup to resolve parent genre IDs.
     */
    private fun Genre.toEntity(pathToId: Map<String, String>): GenreEntity {
        val segments = path.trim('/').split('/')
        val parentPath =
            if (segments.size > 1) {
                "/" + segments.dropLast(1).joinToString("/")
            } else {
                null
            }

        return GenreEntity(
            id = id,
            name = name,
            slug = slug,
            path = path,
            bookCount = bookCount,
            parentId = parentPath?.let { pathToId[it] },
            depth = segments.size - 1,
            sortOrder = 0, // Server doesn't provide this, use default
        )
    }
}
