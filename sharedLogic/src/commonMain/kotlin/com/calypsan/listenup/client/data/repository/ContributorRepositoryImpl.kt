package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ContributorWithAliases
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.toListItem
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.dto.ContributorHit
import com.calypsan.listenup.api.dto.SearchQuery
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.repository.common.QueryUtils
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.model.ContributorSearchResponse
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.domain.model.RoleWithBookCount
import com.calypsan.listenup.client.domain.repository.BookWithContributorRole
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
 * - Delete operations
 *
 * [observeById] layers a "Never Stranded" RPC-fallback on top of the Room
 * observable: when Room yields `null` (the contributor is not cached yet) and
 * the device is online, a single on-demand [ContributorService.getContributor]
 * call is fired and the result is written through Room via
 * [com.calypsan.listenup.client.data.sync.domains.contributorsDomain]'s
 * `onCatchUpItem`. The Room Flow then re-emits
 * with the now-present contributor. Offline cache misses skip the RPC entirely
 * and stay `null`.
 *
 * @property contributorDao Room DAO for contributor operations
 * @property bookDao Room DAO for book operations
 * @property searchDao Room DAO for FTS search
 * @property networkMonitor For checking online/offline status
 * @property imageStorage For resolving cover image paths
 * @property channel [RpcChannel] over [com.calypsan.listenup.api.ContributorService]
 *   for on-demand cache-miss fetches (bounded, self-healing).
 * @property searchChannel [RpcChannel] over the unified [com.calypsan.listenup.api.SearchService]
 *   backing the never-stranded server contributor autocomplete (bounded, self-healing).
 * @property contributorSyncHandler Owns the atomic aggregate write-through
 *   used to cache an on-demand-fetched contributor into Room.
 */
internal class ContributorRepositoryImpl(
    private val contributorDao: ContributorDao,
    private val bookDao: BookDao,
    private val searchDao: SearchDao,
    private val networkMonitor: NetworkMonitor,
    private val imageStorage: ImageStorage,
    private val channel: RpcChannel<ContributorService>,
    private val searchChannel: RpcChannel<SearchService>,
    private val contributorSyncHandler: SyncDomainHandler<ContributorSyncPayload>,
) : ContributorRepository {
    // ========== Basic Observation Methods ==========

    override fun observeAll(): Flow<List<Contributor>> =
        contributorDao.observeAllWithAliases().map { rows ->
            rows.map { it.toDomain() }
        }

    /**
     * Observe a contributor by id, with a never-stranded cache-miss fallback.
     *
     * When Room yields `null` (the contributor is not yet cached) and the device
     * is online, fires a single on-demand [ContributorService.getContributor]
     * fetch and writes the result through Room via
     * [com.calypsan.listenup.client.data.sync.domains.contributorsDomain]. The Room Flow then re-emits with the
     * now-present contributor. The fetch is fired at most once per collection —
     * [attemptedFetch] guards against a persistently-null emission re-firing the
     * RPC on a loop. Offline cache misses and RPC failures degrade silently to
     * continued `null` emissions ("Never Stranded").
     */
    override fun observeById(id: String): Flow<Contributor?> {
        val contributorId = ContributorId(id)
        var attemptedFetch = false
        return contributorDao
            .observeByIdWithAliases(id)
            .onEach { row ->
                if (row == null && !attemptedFetch && networkMonitor.isOnline()) {
                    attemptedFetch = true
                    fetchAndCacheContributor(contributorId)
                }
            }.map { row -> row?.toDomain() }
    }

    /**
     * One-shot on-demand fetch for a cache-missing contributor. Dispatches through the
     * [channel] (which folds transport faults to a typed [AppResult.Failure] and
     * re-raises [kotlin.coroutines.cancellation.CancellationException], so structured
     * concurrency is preserved without a manual catch), and writes a returned entity
     * through Room via the shared sync handler. A [AppResult.Failure] is logged and
     * left as a cache miss — the observer keeps emitting `null` rather than crashing
     * ("Never Stranded").
     */
    private suspend fun fetchAndCacheContributor(id: ContributorId) {
        when (val result = channel.call { it.getContributor(id) }) {
            is AppResult.Success -> {
                result.data?.let { contributorSyncHandler.onCatchUpItem(it, isTombstone = false) }
                    ?: logger.debug { "getContributor returned no contributor for $id — leaving cache miss" }
            }

            is AppResult.Failure -> {
                logger.warn {
                    "On-demand getContributor failed for $id (${result.error.code}) — staying with cache miss"
                }
            }
        }
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

        // Try server search if online; on Failure fall back to local FTS (never-stranded pattern)
        if (networkMonitor.isOnline()) {
            val serverResult = searchServer(sanitizedQuery, limit)
            if (serverResult != null) return serverResult
        }

        // Offline or server failed - use local FTS
        return searchLocal(sanitizedQuery, limit)
    }

    /**
     * Attempt a server-side contributor search via the unified [SearchService], reading the
     * `contributors` slice of the [com.calypsan.listenup.api.dto.SearchResults] envelope. Returns
     * `null` on [AppResult.Failure] so the caller can fall back to local FTS (never-stranded
     * pattern). The [searchChannel] folds transport faults to a typed failure and re-raises
     * CancellationException, so coroutine cancellation is never swallowed.
     */
    private suspend fun searchServer(
        query: String,
        limit: Int,
    ): ContributorSearchResponse? =
        withContext(IODispatcher) {
            val (result, duration) =
                measureTimedValue { searchChannel.call { it.search(SearchQuery(text = query, limit = limit)) } }

            when (result) {
                is AppResult.Success -> {
                    val contributors = result.data.contributors.map { it.toDomain() }
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

                is AppResult.Failure -> {
                    logger.warn {
                        "Server contributor search failed, falling back to local FTS: ${result.error.message}"
                    }
                    null
                }
            }
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
        sortName = sortName,
        asin = asin,
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
        sortName = contributor.sortName,
        asin = contributor.asin,
    )

private fun ContributorEntity.toSearchResult(): ContributorSearchResult =
    ContributorSearchResult(
        id = id.value,
        name = name,
        bookCount = 0, // Not available in offline mode
    )

private fun ContributorHit.toDomain(): ContributorSearchResult =
    ContributorSearchResult(
        id = id.value,
        name = name,
        bookCount = bookCount,
    )

// ========== Domain to Entity Mappers ==========

private fun Contributor.toEntity(): ContributorEntity {
    val now =
        com.calypsan.listenup.core.Timestamp(
            com.calypsan.listenup.core
                .currentEpochMilliseconds(),
        )
    return ContributorEntity(
        id = id,
        name = name,
        sortName = sortName,
        asin = asin,
        description = description,
        imagePath = imagePath,
        imageBlurHash = imageBlurHash,
        website = website,
        birthDate = birthDate,
        deathDate = deathDate,
        createdAt = now,
        updatedAt = now,
    )
}
