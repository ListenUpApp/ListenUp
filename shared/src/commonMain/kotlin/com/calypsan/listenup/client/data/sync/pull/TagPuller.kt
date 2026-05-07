package com.calypsan.listenup.client.data.sync.pull

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.TagDao
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.remote.TagApiContract
import com.calypsan.listenup.client.data.sync.model.SyncPhase
import com.calypsan.listenup.client.data.sync.model.SyncStatus
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Handles syncing global tags from server.
 *
 * Only syncs the global tag list. Book-tag relationships are synced inline
 * with books via BookPuller when the server includes tags in BookResponse.
 */
class TagPuller(
    private val tagApi: TagApiContract,
    private val tagDao: TagDao,
) : Puller {
    /**
     * Pull global tags from server.
     *
     * @param updatedAfter ISO timestamp for delta sync (currently ignored - full sync only)
     * @param onProgress Callback for progress updates
     */
    override suspend fun pull(
        updatedAfter: String?,
        onProgress: (SyncStatus) -> Unit,
    ) {
        logger.debug { "Starting tag sync..." }

        onProgress(
            SyncStatus.Progress(
                phase = SyncPhase.SYNCING_TAGS,
                phaseItemsSynced = 0,
                phaseTotalItems = -1,
                message = "Syncing tags...",
            ),
        )

        // Fetch all global tags
        // Book-tag relationships are synced inline with BookPuller
        when (val result = tagApi.listTags()) {
            is AppResult.Success -> {
                val tags = result.data
                logger.info { "Fetched ${tags.size} global tags" }

                // Convert to entities and upsert
                val tagEntities =
                    tags.map { tag ->
                        TagEntity(
                            id = tag.id,
                            slug = tag.slug,
                            bookCount = tag.bookCount,
                            createdAt =
                                tag.createdAt?.let { Timestamp(it.toEpochMilliseconds()) }
                                    ?: Timestamp.now(),
                        )
                    }
                tagDao.upsertAll(tagEntities)
                logger.info { "Tag sync complete: ${tagEntities.size} tags synced" }
            }

            is AppResult.Failure -> {
                logger.warn { "Failed to fetch global tags: ${result.error.message}" }
                // Don't throw - tags are not critical for sync
            }
        }
    }
}
