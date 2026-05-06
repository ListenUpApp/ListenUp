@file:Suppress("ktlint:standard:max-line-length")

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.IODispatcher
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ContributorWithAliases
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.toListItem
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.data.remote.ApplyContributorMetadataResult
import com.calypsan.listenup.client.data.remote.ContributorApiContract
import com.calypsan.listenup.client.data.remote.MetadataApiContract
import com.calypsan.listenup.client.data.repository.common.QueryUtils
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.model.ContributorMetadataCandidate
import com.calypsan.listenup.client.domain.model.ContributorMetadataResult
import com.calypsan.listenup.client.domain.model.ContributorSearchResponse
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.domain.model.RoleWithBookCount
import com.calypsan.listenup.client.domain.repository.BookWithContributorRole
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.core.error.AppException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.measureTimedValue

private val logger = KotlinLogging.logger {}

/**
 * Implementation of the domain ContributorRepository using Room.
 *
 * Provides:
 * - Reactive (Flow-based) and one-shot queries for contributors
 * - Library view methods (contributors by role with book counts)
 * - Contributor detail methods (roles with counts, books per role)
 * - Search with "never stranded" pattern (server with local fallback)
 * - Metadata operations (apply from Audible)
 * - Delete operations
 *
 * @property contributorDao Room DAO for contributor operations
 * @property bookDao Room DAO for book operations
 * @property searchDao Room DAO for FTS search
 * @property api Server API client for contributor operations
 * @property metadataApi API client for Audible metadata
 * @property networkMonitor For checking online/offline status
 * @property imageStorage For resolving cover image paths
 */
class ContributorRepositoryImpl(
    private val contributorDao: ContributorDao,
    private val bookDao: BookDao,
    private val searchDao: SearchDao,
    private val api: ContributorApiContract,
    private val metadataApi: MetadataApiContract,
    private val networkMonitor: NetworkMonitor,
    private val imageStorage: ImageStorage,
) : ContributorRepository {
    // ========== Basic Observation Methods ==========

    override fun observeAll(): Flow<List<Contributor>> =
        contributorDao.observeAllWithAliases().map { rows ->
            rows.map { it.toDomain() }
        }

    override fun observeById(id: String): Flow<Contributor?> =
        contributorDao.observeByIdWithAliases(id).map { row ->
            row?.toDomain()
        }

    override suspend fun getById(id: String): Contributor? = contributorDao.getByIdWithAliases(id)?.toDomain()

    override fun observeByBookId(bookId: String): Flow<List<Contributor>> =
        contributorDao.observeByBookId(bookId).map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun getByBookId(bookId: String): List<Contributor> =
        contributorDao.getByBookId(bookId).map { it.toDomain() }

    override suspend fun getBookIdsForContributor(contributorId: String): List<String> =
        contributorDao.getBookIdsForContributor(contributorId)

    override fun observeBookIdsForContributor(contributorId: String): Flow<List<String>> =
        contributorDao.observeBookIdsForContributor(contributorId)

    // ========== Library View Methods ==========

    override fun observeContributorsByRole(role: String): Flow<List<ContributorWithBookCount>> =
        contributorDao.observeByRoleWithCount(role).map { entities ->
            entities.map { entity ->
                ContributorWithBookCount(
                    contributor = entity.contributor.toDomain(),
                    bookCount = entity.bookCount,
                )
            }
        }

    // ========== Contributor Detail Methods ==========

    override fun observeRolesWithCountForContributor(contributorId: String): Flow<List<RoleWithBookCount>> =
        contributorDao.observeRolesWithCountForContributor(contributorId).map { entities ->
            entities.map { entity ->
                RoleWithBookCount(
                    role = entity.role,
                    bookCount = entity.bookCount,
                )
            }
        }

    override fun observeBooksForContributorRole(
        contributorId: String,
        role: String,
    ): Flow<List<BookWithContributorRole>> =
        bookDao.observeByContributorAndRole(contributorId, role).map { booksWithContributors ->
            booksWithContributors.map { bwc ->
                val creditedAs =
                    bwc.contributorRoles
                        .find {
                            it.contributorId.value == contributorId && it.role == role
                        }?.creditedAs
                BookWithContributorRole(book = bwc.toListItem(imageStorage), creditedAs = creditedAs)
            }
        }

    // ========== Search Methods ==========

    override suspend fun searchContributors(
        query: String,
        limit: Int,
    ): ContributorSearchResponse {
        val sanitizedQuery = QueryUtils.sanitize(query)
        if (sanitizedQuery.isBlank() || sanitizedQuery.length < 2) {
            return ContributorSearchResponse(
                contributors = emptyList(),
                isOfflineResult = false,
                tookMs = 0,
            )
        }

        // Try server search if online
        if (networkMonitor.isOnline()) {
            try {
                return searchServer(sanitizedQuery, limit)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Server contributor search failed, falling back to local FTS" }
            }
        }

        // Offline or server failed - use local FTS
        return searchLocal(sanitizedQuery, limit)
    }

    private suspend fun searchServer(
        query: String,
        limit: Int,
    ): ContributorSearchResponse =
        withContext(IODispatcher) {
            val (contributors, duration) =
                measureTimedValue {
                    when (val result = api.searchContributors(query, limit)) {
                        is Success -> result.data.map { it.toDomain() }
                        is Failure -> throw AppException(result.error)
                    }
                }

            logger.debug {
                "Server contributor search: query='$query', " +
                    "results=${contributors.size}, took=${duration.inWholeMilliseconds}ms"
            }

            ContributorSearchResponse(
                contributors = contributors,
                isOfflineResult = false,
                tookMs = duration.inWholeMilliseconds,
            )
        }

    private suspend fun searchLocal(
        query: String,
        limit: Int,
    ): ContributorSearchResponse =
        withContext(IODispatcher) {
            val (entities, duration) =
                measureTimedValue {
                    val ftsQuery = QueryUtils.toFtsQuery(query)
                    try {
                        searchDao.searchContributors(ftsQuery, limit)
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "Contributor FTS search failed" }
                        emptyList()
                    }
                }

            val contributors = entities.map { it.toSearchResult() }

            logger.debug {
                "Local contributor search: query='$query', " +
                    "results=${contributors.size}, took=${duration.inWholeMilliseconds}ms"
            }

            ContributorSearchResponse(
                contributors = contributors,
                isOfflineResult = true,
                tookMs = duration.inWholeMilliseconds,
            )
        }

    // ========== Mutation Methods ==========

    override suspend fun upsertContributor(contributor: Contributor) {
        withContext(IODispatcher) {
            contributorDao.upsert(contributor.toEntity())
            logger.debug { "Upserted contributor ${contributor.id}" }
        }
    }

    override suspend fun deleteContributor(contributorId: String): AppResult<Unit> =
        suspendRunCatching {
            withContext(IODispatcher) {
                api.deleteContributor(contributorId)
                logger.info { "Deleted contributor $contributorId" }
            }
        }

    override suspend fun applyMetadataFromAudible(
        contributorId: String,
        asin: String,
        imageUrl: String?,
        applyName: Boolean,
        applyBiography: Boolean,
        applyImage: Boolean,
    ): ContributorMetadataResult =
        withContext(IODispatcher) {
            try {
                when (
                    val result =
                        metadataApi.applyContributorMetadata(
                            contributorId = contributorId,
                            asin = asin,
                            imageUrl = imageUrl,
                            applyName = applyName,
                            applyBiography = applyBiography,
                            applyImage = applyImage,
                        )
                ) {
                    is ApplyContributorMetadataResult.Success -> {
                        logger.info { "Applied Audible metadata to contributor $contributorId" }
                        // Simple success without contributor data
                        ContributorMetadataResult.Success()
                    }

                    is ApplyContributorMetadataResult.NeedsDisambiguation -> {
                        logger.debug {
                            "Contributor metadata needs disambiguation: " +
                                "${result.candidates.size} candidates for '${result.searchedName}'"
                        }
                        ContributorMetadataResult.NeedsDisambiguation(
                            options = result.candidates.map { it.toDomain() },
                        )
                    }

                    is ApplyContributorMetadataResult.Error -> {
                        logger.warn { "Failed to apply contributor metadata: ${result.message}" }
                        ContributorMetadataResult.Error(result.message)
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error applying contributor metadata" }
                ContributorMetadataResult.Error(e.message ?: "Unknown error")
            }
        }
}

// ========== Entity to Domain Mappers ==========

/**
 * Entity-only mapper used by paths that don't carry aliases (observeByBookId,
 * getByBookId, observeContributorsByRole). Aliases are intentionally empty here —
 * those consumers don't display alias lists, so paying the junction-join cost is
 * wasted work. Callers that need aliases use [ContributorWithAliases.toDomain].
 */
private fun ContributorEntity.toDomain(): Contributor =
    Contributor(
        id = id,
        name = name,
        description = description,
        imagePath = imagePath,
        imageBlurHash = imageBlurHash,
        website = website,
        birthDate = birthDate,
        deathDate = deathDate,
        aliases = emptyList(),
    )

private fun ContributorWithAliases.toDomain(): Contributor =
    Contributor(
        id = contributor.id,
        name = contributor.name,
        description = contributor.description,
        imagePath = contributor.imagePath,
        imageBlurHash = contributor.imageBlurHash,
        website = contributor.website,
        birthDate = contributor.birthDate,
        deathDate = contributor.deathDate,
        aliases = aliases.sortedWith(String.CASE_INSENSITIVE_ORDER),
    )

private fun ContributorEntity.toSearchResult(): ContributorSearchResult =
    ContributorSearchResult(
        id = id.value,
        name = name,
        bookCount = 0, // Not available in offline mode
    )

private fun com.calypsan.listenup.client.data.remote.ContributorSearchResult.toDomain(): ContributorSearchResult =
    ContributorSearchResult(
        id = id,
        name = name,
        bookCount = bookCount,
    )

private fun com.calypsan.listenup.client.data.remote.model.ContributorMetadataSearchResult.toDomain(): ContributorMetadataCandidate =
    ContributorMetadataCandidate(
        asin = asin,
        name = name,
        imageUrl = imageUrl,
        description = description,
    )

// ========== Domain to Entity Mappers ==========

private fun Contributor.toEntity(): ContributorEntity {
    val now =
        com.calypsan.listenup.client.core.Timestamp(
            com.calypsan.listenup.client.core
                .currentEpochMilliseconds(),
        )
    return ContributorEntity(
        id = id,
        name = name,
        description = description,
        imagePath = imagePath,
        imageBlurHash = imageBlurHash,
        website = website,
        birthDate = birthDate,
        deathDate = deathDate,
        // SYNCED because this entity was just received from the server (via metadata API)
        syncState = com.calypsan.listenup.client.data.local.db.SyncState.SYNCED,
        lastModified = now,
        serverVersion = now,
        createdAt = now,
        updatedAt = now,
    )
}
