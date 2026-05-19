package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.client.data.remote.MetadataApiContract
import com.calypsan.listenup.client.domain.model.ContributorMetadataCandidate
import com.calypsan.listenup.client.domain.model.ContributorMetadataResult
import com.calypsan.listenup.client.domain.model.ContributorWithMetadata
import com.calypsan.listenup.client.domain.repository.ApplyMatchRequest
import com.calypsan.listenup.client.domain.repository.ContributorMetadataProfile
import com.calypsan.listenup.client.domain.repository.CoverOption
import com.calypsan.listenup.client.domain.repository.MatchFields
import com.calypsan.listenup.client.domain.repository.MetadataBook
import com.calypsan.listenup.client.domain.repository.MetadataContributor
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.client.domain.repository.MetadataSearchResult
import com.calypsan.listenup.client.domain.repository.MetadataSeriesEntry
import com.calypsan.listenup.client.domain.repository.SeriesMatchEntry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext
import com.calypsan.listenup.client.data.remote.ApplyContributorMetadataResult as ApiContributorMetadataResult
import com.calypsan.listenup.client.data.remote.model.ApplyMatchRequest as DataApplyMatchRequest
import com.calypsan.listenup.client.data.remote.model.CoverOption as DataCoverOption
import com.calypsan.listenup.client.data.remote.model.MetadataBook as DataMetadataBook
import com.calypsan.listenup.client.data.remote.model.MetadataSearchResult as DataMetadataSearchResult
import com.calypsan.listenup.core.Success

private val logger = KotlinLogging.logger {}

/**
 * Repository for Audible metadata operations.
 *
 * This is an online-only feature - there's no offline fallback
 * since we're fetching external metadata from Audible.
 *
 * Implements the domain MetadataRepository interface, converting
 * between data layer types and domain types.
 *
 * @property metadataApi Audible metadata API client
 */
class MetadataRepositoryImpl(
    private val metadataApi: MetadataApiContract,
) : MetadataRepository {
    /**
     * Search Audible for matching audiobooks.
     */
    override suspend fun searchAudible(
        query: String,
        region: String,
    ): List<MetadataSearchResult> =
        withContext(IODispatcher) {
            metadataApi.search(query, region).map { it.toDomain() }
        }

    /**
     * Get full metadata for a specific Audible book.
     */
    override suspend fun getMetadataPreview(
        asin: String,
        region: String,
    ): MetadataBook =
        withContext(IODispatcher) {
            metadataApi.getBook(asin, region).toDomain()
        }

    /**
     * Apply Audible metadata match to a book.
     */
    override suspend fun applyMatch(
        bookId: String,
        request: ApplyMatchRequest,
    ) {
        withContext(IODispatcher) {
            metadataApi.applyMatch(bookId, request.toData())
        }
    }

    /**
     * Search for cover images from multiple sources.
     */
    override suspend fun searchCovers(
        title: String,
        author: String,
    ): List<CoverOption> =
        withContext(IODispatcher) {
            metadataApi.searchCovers(title, author).map { it.toDomain() }
        }

    /**
     * Apply Audible metadata to a contributor.
     */
    override suspend fun applyContributorMetadata(
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
                    is ApiContributorMetadataResult.Success -> {
                        logger.info { "Applied Audible metadata to contributor $contributorId" }
                        val contributor = result.contributor
                        ContributorMetadataResult.Success(
                            contributor =
                                ContributorWithMetadata(
                                    id = contributor.id,
                                    name = contributor.name,
                                    biography = contributor.biography,
                                    imageUrl = contributor.imageUrl,
                                    imageBlurHash = contributor.imageBlurHash,
                                ),
                        )
                    }

                    is ApiContributorMetadataResult.NeedsDisambiguation -> {
                        logger.debug {
                            "Contributor metadata needs disambiguation: ${result.candidates.size} candidates"
                        }
                        ContributorMetadataResult.NeedsDisambiguation(
                            options =
                                result.candidates.map { candidate ->
                                    ContributorMetadataCandidate(
                                        asin = candidate.asin,
                                        name = candidate.name,
                                        imageUrl = candidate.imageUrl,
                                        description = candidate.description,
                                    )
                                },
                        )
                    }

                    is ApiContributorMetadataResult.Error -> {
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

    /**
     * Search Audible for matching contributors.
     */
    override suspend fun searchContributors(
        query: String,
        region: String,
    ): List<ContributorMetadataCandidate> =
        withContext(IODispatcher) {
            metadataApi.searchContributors(query, region).map { result ->
                ContributorMetadataCandidate(
                    asin = result.asin,
                    name = result.name,
                    imageUrl = result.imageUrl,
                    description = result.description,
                )
            }
        }

    /**
     * Get full contributor profile from Audible.
     */
    override suspend fun getContributorProfile(asin: String): ContributorMetadataProfile =
        withContext(IODispatcher) {
            val profile = metadataApi.getContributorProfile(asin)
            ContributorMetadataProfile(
                asin = profile.asin,
                name = profile.name,
                biography = profile.biography,
                imageUrl = profile.imageUrl,
            )
        }
}

// ========== Type Conversions ==========

/**
 * Convert data layer search result to domain type.
 */
private fun DataMetadataSearchResult.toDomain(): MetadataSearchResult =
    MetadataSearchResult(
        asin = asin,
        title = title,
        subtitle = subtitle,
        authors = authors.map { it.name },
        narrators = narrators.map { it.name },
        releaseDate = releaseDate,
        runtimeMinutes = runtimeMinutes,
        coverUrl = coverUrl,
        publisher = null, // Data layer search result doesn't have publisher
        language = language,
        rating = rating,
        ratingCount = ratingCount,
    )

/**
 * Convert data layer book metadata to domain type.
 */
private fun DataMetadataBook.toDomain(): MetadataBook =
    MetadataBook(
        asin = asin,
        title = title,
        subtitle = subtitle,
        description = description,
        authors = authors.map { MetadataContributor(asin = it.asin, name = it.name) },
        narrators = narrators.map { MetadataContributor(asin = it.asin, name = it.name) },
        releaseDate = releaseDate,
        runtimeMinutes = runtimeMinutes,
        coverUrl = coverUrl,
        publisher = publisher,
        language = language,
        series = series.map { MetadataSeriesEntry(asin = it.asin, name = it.name, position = it.position) },
        genres = genres,
        rating = rating,
        ratingCount = ratingCount,
    )

/**
 * Convert data layer cover option to domain type.
 */
private fun DataCoverOption.toDomain(): CoverOption =
    CoverOption(
        url = url,
        source = source,
        width = width,
        height = height,
    )

/**
 * Convert domain apply match request to data layer type.
 */
private fun ApplyMatchRequest.toData(): DataApplyMatchRequest =
    DataApplyMatchRequest(
        asin = asin,
        region = region,
        fields =
            com.calypsan.listenup.client.data.remote.model.MatchFields(
                title = fields.title,
                subtitle = fields.subtitle,
                description = fields.description,
                publisher = fields.publisher,
                releaseDate = fields.releaseDate,
                language = fields.language,
                cover = fields.cover,
            ),
        authors = authors,
        narrators = narrators,
        series =
            series.map {
                com.calypsan.listenup.client.data.remote.model.SeriesMatchEntry(
                    asin = it.asin,
                    applyName = it.applyName,
                    applySequence = it.applySequence,
                )
            },
        genres = genres,
        coverUrl = coverUrl,
    )
