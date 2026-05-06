package com.calypsan.listenup.client.data.sync.pull

import com.calypsan.listenup.client.core.ContributorId
import com.calypsan.listenup.client.data.local.db.ContributorAliasCrossRef
import com.calypsan.listenup.client.data.local.db.ContributorAliasDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.SyncApiContract
import com.calypsan.listenup.client.data.remote.model.toEntity
import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
import com.calypsan.listenup.client.data.sync.model.SyncPhase
import com.calypsan.listenup.client.data.sync.model.SyncStatus
import com.calypsan.listenup.client.core.error.AppException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Failure

private val logger = KotlinLogging.logger {}

/**
 * Handles paginated contributor fetching and image downloads.
 */
class ContributorPuller(
    private val transactionRunner: TransactionRunner,
    private val syncApi: SyncApiContract,
    private val contributorDao: ContributorDao,
    private val contributorAliasDao: ContributorAliasDao,
    private val imageDownloader: ImageDownloaderContract,
    private val scope: CoroutineScope,
) : Puller {
    /**
     * Pull all contributors from server with pagination.
     *
     * @param updatedAfter ISO timestamp for delta sync, null for full sync
     * @param onProgress Callback for progress updates
     */
    override suspend fun pull(
        updatedAfter: String?,
        onProgress: (SyncStatus) -> Unit,
    ) {
        var cursor: String? = null
        var hasMore = true
        val limit = 100
        var itemsSynced = 0
        var totalDeleted = 0

        val contributorsWithImages =
            buildList {
                while (hasMore) {
                    when (
                        val result =
                            syncApi.getContributors(
                                limit = limit,
                                cursor = cursor,
                                updatedAfter = updatedAfter,
                            )
                    ) {
                        is Success -> {
                            val response = result.data
                            cursor = response.nextCursor
                            hasMore = response.hasMore

                            val serverContributors = response.contributors.map { it.toEntity() }
                            val deletedContributorIds = response.deletedContributorIds

                            // Track contributors with images for later download
                            response.contributors
                                .filter { !it.imageUrl.isNullOrBlank() }
                                .forEach { add(it.id) }

                            logger.debug {
                                "Fetched batch: ${serverContributors.size} contributors, ${deletedContributorIds.size} deletions"
                            }

                            // Handle deletions
                            if (deletedContributorIds.isNotEmpty()) {
                                contributorDao.deleteByIds(deletedContributorIds)
                                totalDeleted += deletedContributorIds.size
                                logger.info { "Removed ${deletedContributorIds.size} contributors deleted on server" }
                            }

                            if (serverContributors.isNotEmpty()) {
                                // Preserve existing local imagePath — the server entity has
                                // imagePath=null because image URLs need local download first.
                                // Without this, sync overwrites downloaded images with null.
                                val existingIds = serverContributors.map { it.id.value }
                                val existingPaths =
                                    existingIds
                                        .mapNotNull { id ->
                                            contributorDao.getById(id)?.let { it.id.value to it.imagePath }
                                        }.toMap()

                                val merged =
                                    serverContributors.map { entity ->
                                        val existingPath = existingPaths[entity.id.value]
                                        if (entity.imagePath == null && existingPath != null) {
                                            entity.copy(imagePath = existingPath)
                                        } else {
                                            entity
                                        }
                                    }

                                val aliasRows =
                                    response.contributors.flatMap { resp ->
                                        resp.aliases
                                            .orEmpty()
                                            .distinctBy { it.lowercase() }
                                            .map {
                                                ContributorAliasCrossRef(
                                                    contributorId = ContributorId(resp.id),
                                                    alias = it,
                                                )
                                            }
                                    }

                                transactionRunner.atomically {
                                    contributorDao.upsertAll(merged)
                                    merged.forEach { entity ->
                                        contributorAliasDao.deleteForContributor(entity.id.value)
                                    }
                                    if (aliasRows.isNotEmpty()) {
                                        contributorAliasDao.insertAll(aliasRows)
                                    }
                                }
                            }

                            itemsSynced += serverContributors.size + deletedContributorIds.size
                            onProgress(
                                SyncStatus.Progress(
                                    phase = SyncPhase.SYNCING_CONTRIBUTORS,
                                    phaseItemsSynced = itemsSynced,
                                    phaseTotalItems = -1,
                                    message = "Syncing contributors: $itemsSynced synced...",
                                ),
                            )
                        }

                        is Failure -> {
                            throw AppException(result.error)
                        }
                    }
                }
            }

        logger.info { "Contributors sync complete: $itemsSynced items processed" }

        // Download contributor images in background
        if (contributorsWithImages.isNotEmpty()) {
            scope.launch {
                logger.info { "Starting image downloads for ${contributorsWithImages.size} contributors..." }
                val downloadedIds = imageDownloader.downloadContributorImages(contributorsWithImages)

                if (downloadedIds is Success && downloadedIds.data.isNotEmpty()) {
                    downloadedIds.data.forEach { contributorId ->
                        val localPath = imageDownloader.getContributorImagePath(contributorId)
                        if (localPath != null) {
                            contributorDao.updateImagePath(contributorId, localPath)
                        }
                    }
                    logger.info { "Updated ${downloadedIds.data.size} contributors with local image paths" }
                }
            }
        }
    }
}
