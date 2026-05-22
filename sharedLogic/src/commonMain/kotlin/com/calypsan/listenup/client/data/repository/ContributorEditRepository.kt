@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookContributorCrossRef
import com.calypsan.listenup.client.data.local.db.BookContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorAliasCrossRef
import com.calypsan.listenup.client.data.local.db.ContributorAliasDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.util.NanoId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

/**
 * Data class for contributor update request.
 */
data class ContributorUpdateRequest(
    val name: String? = null,
    val biography: String? = null,
    val website: String? = null,
    val birthDate: String? = null,
    val deathDate: String? = null,
    val aliases: List<String>? = null,
    val imagePath: String? = null,
)

/**
 * Contract for contributor editing operations.
 *
 * Provides methods for modifying contributor metadata and managing aliases.
 * Changes are applied locally immediately; server propagation for contributor
 * edits is a Books-C concern and is not yet wired.
 */
interface ContributorEditRepositoryContract {
    /**
     * Update contributor metadata.
     *
     * Applies update locally. Only non-null fields are updated (PATCH semantics).
     *
     * @param contributorId ID of the contributor to update
     * @param update Fields to update
     * @return Result indicating success or failure
     */
    suspend fun updateContributor(
        contributorId: String,
        update: ContributorUpdateRequest,
    ): AppResult<Unit>

    /**
     * Merge a source contributor into a target contributor.
     *
     * Local effects (applied immediately):
     * - Re-links all book relationships from source to target (with creditedAs)
     * - Adds source name to target's aliases
     * - Deletes source contributor
     *
     * @param targetId ID of the target contributor (receives the merge)
     * @param sourceId ID of the source contributor (will be deleted)
     * @return Result indicating success or failure
     */
    suspend fun mergeContributor(
        targetId: String,
        sourceId: String,
    ): AppResult<Unit>

    /**
     * Unmerge an alias from a contributor, creating a new contributor.
     *
     * Local effects (applied immediately):
     * - Creates new contributor with the alias name (temporary local ID)
     * - Re-links book relationships where creditedAs matches the alias
     * - Removes alias from original contributor
     *
     * @param contributorId ID of the contributor to unmerge from
     * @param aliasName Name of the alias to split out
     * @return Result indicating success or failure
     */
    suspend fun unmergeContributor(
        contributorId: String,
        aliasName: String,
    ): AppResult<Unit>
}

/**
 * Repository for contributor editing operations using an offline-first pattern.
 *
 * Edits are applied optimistically to Room inside a [TransactionRunner.atomically] block
 * and returned as success immediately. Server propagation for contributor edits is a
 * Books-C concern and is not yet wired.
 *
 * @property transactionRunner For atomic multi-DAO writes
 * @property contributorDao Room DAO for contributor operations
 * @property contributorAliasDao Room DAO for contributor alias operations
 * @property bookContributorDao Room DAO for book-contributor relationships
 */
class ContributorEditRepository(
    private val transactionRunner: TransactionRunner,
    private val contributorDao: ContributorDao,
    private val contributorAliasDao: ContributorAliasDao,
    private val bookContributorDao: BookContributorDao,
) : ContributorEditRepositoryContract,
    com.calypsan.listenup.client.domain.repository.ContributorEditRepository {
    // ========== Domain Interface Implementation ==========

    /**
     * Domain interface method - adapts to internal updateContributor implementation.
     */
    override suspend fun updateContributor(
        contributorId: String,
        name: String?,
        biography: String?,
        website: String?,
        birthDate: String?,
        deathDate: String?,
        aliases: List<String>?,
    ): AppResult<Unit> =
        updateContributor(
            contributorId,
            ContributorUpdateRequest(
                name = name,
                biography = biography,
                website = website,
                birthDate = birthDate,
                deathDate = deathDate,
                aliases = aliases,
            ),
        )

    // ========== Data Layer Contract Implementation ==========

    /**
     * Update contributor metadata.
     */
    override suspend fun updateContributor(
        contributorId: String,
        update: ContributorUpdateRequest,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            logger.debug { "Updating contributor (offline-first): $contributorId" }

            val existing = contributorDao.getById(contributorId)
            if (existing == null) {
                logger.error { "Contributor not found: $contributorId" }
                return@withContext Failure(Exception("Contributor not found: $contributorId"))
            }

            val updated =
                existing.copy(
                    name = update.name ?: existing.name,
                    description = update.biography ?: existing.description,
                    website = update.website ?: existing.website,
                    birthDate = update.birthDate ?: existing.birthDate,
                    deathDate = update.deathDate ?: existing.deathDate,
                    imagePath = update.imagePath ?: existing.imagePath,
                )

            val newAliasRows =
                update.aliases
                    ?.distinctBy { it.lowercase() }
                    ?.map { ContributorAliasCrossRef(ContributorId(contributorId), it) }

            transactionRunner.atomically {
                contributorDao.upsert(updated)
                if (newAliasRows != null) {
                    contributorAliasDao.deleteForContributor(contributorId)
                    if (newAliasRows.isNotEmpty()) {
                        contributorAliasDao.insertAll(newAliasRows)
                    }
                }
            }

            logger.info { "Contributor updated locally: $contributorId" }
            Success(Unit)
        }

    /**
     * Merge a source contributor into a target contributor.
     */
    override suspend fun mergeContributor(
        targetId: String,
        sourceId: String,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            logger.debug { "Merging contributor $sourceId into $targetId (offline-first)" }

            val target = contributorDao.getById(targetId)
            if (target == null) {
                logger.error { "Target contributor not found: $targetId" }
                return@withContext Failure(Exception("Target contributor not found: $targetId"))
            }

            val source = contributorDao.getById(sourceId)
            if (source == null) {
                logger.error { "Source contributor not found: $sourceId" }
                return@withContext Failure(Exception("Source contributor not found: $sourceId"))
            }

            if (targetId == sourceId) {
                logger.error { "Cannot merge contributor into itself" }
                return@withContext Failure(Exception("Cannot merge contributor into itself"))
            }

            val sourceRelations = bookContributorDao.getByContributorId(sourceId)
            val currentTargetAliases = contributorAliasDao.getForContributor(targetId)
            val newAliases = (currentTargetAliases + source.name).distinctBy { it.lowercase() }
            val newAliasRows =
                newAliases.map { ContributorAliasCrossRef(ContributorId(targetId), it) }

            val updatedTarget = target.copy(updatedAt = Timestamp.now())

            transactionRunner.atomically {
                for (relation in sourceRelations) {
                    val existingTarget =
                        bookContributorDao.get(
                            relation.bookId,
                            targetId,
                            relation.role,
                        )
                    if (existingTarget == null) {
                        val newRelation =
                            BookContributorCrossRef(
                                bookId = relation.bookId,
                                contributorId = ContributorId(targetId),
                                role = relation.role,
                                creditedAs = relation.creditedAs ?: source.name,
                            )
                        bookContributorDao.insert(newRelation)
                    }
                    bookContributorDao.delete(relation.bookId, sourceId, relation.role)
                }

                contributorDao.upsert(updatedTarget)
                contributorAliasDao.deleteForContributor(targetId)
                if (newAliasRows.isNotEmpty()) {
                    contributorAliasDao.insertAll(newAliasRows)
                }
                contributorDao.deleteById(sourceId)
            }

            logger.info { "Contributor merge applied locally: $sourceId -> $targetId" }
            Success(Unit)
        }

    /**
     * Unmerge an alias from a contributor, creating a new contributor.
     */
    override suspend fun unmergeContributor(
        contributorId: String,
        aliasName: String,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            logger.debug { "Unmerging alias '$aliasName' from contributor $contributorId (offline-first)" }

            val contributor = contributorDao.getById(contributorId)
            if (contributor == null) {
                logger.error { "Contributor not found: $contributorId" }
                return@withContext Failure(Exception("Contributor not found: $contributorId"))
            }

            val currentAliases = contributorAliasDao.getForContributor(contributorId)
            if (!currentAliases.any { it.equals(aliasName, ignoreCase = true) }) {
                logger.error { "Alias '$aliasName' not found for contributor $contributorId" }
                return@withContext Failure(Exception("Alias not found: $aliasName"))
            }

            val tempId = ContributorId(NanoId.generate("temp"))
            val newContributor =
                ContributorEntity(
                    id = tempId,
                    name = aliasName,
                    description = null,
                    imagePath = null,
                    createdAt = Timestamp.now(),
                    updatedAt = Timestamp.now(),
                    website = null,
                    birthDate = null,
                    deathDate = null,
                )

            val relations = bookContributorDao.getByContributorId(contributorId)

            val updatedAliases =
                currentAliases.filter { !it.equals(aliasName, ignoreCase = true) }
            val updatedAliasRows =
                updatedAliases.map { ContributorAliasCrossRef(ContributorId(contributorId), it) }

            val updatedContributor = contributor.copy(updatedAt = Timestamp.now())

            transactionRunner.atomically {
                contributorDao.upsert(newContributor)

                for (relation in relations) {
                    if (relation.creditedAs?.equals(aliasName, ignoreCase = true) == true) {
                        val newRelation =
                            BookContributorCrossRef(
                                bookId = relation.bookId,
                                contributorId = tempId,
                                role = relation.role,
                                creditedAs = null,
                            )
                        bookContributorDao.insert(newRelation)
                        bookContributorDao.delete(relation.bookId, contributorId, relation.role)
                    }
                }

                contributorDao.upsert(updatedContributor)
                contributorAliasDao.deleteForContributor(contributorId)
                if (updatedAliasRows.isNotEmpty()) {
                    contributorAliasDao.insertAll(updatedAliasRows)
                }
            }

            logger.info { "Contributor unmerge applied locally: '$aliasName' from $contributorId (temp ID: $tempId)" }
            Success(Unit)
        }
}
