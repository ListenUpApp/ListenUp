package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.dto.MetadataSearchResults
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.remote.MetadataLookupRpcFactory
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.error.ErrorMapper
import kotlinx.coroutines.CancellationException

/**
 * Repository implementation backing [MetadataRepository] against the
 * [com.calypsan.listenup.api.MetadataLookupService] RPC contract.
 *
 * Each method delegates to the RPC service obtained from [MetadataLookupRpcFactory].
 * [CancellationException] is always re-thrown per the Error Architecture rule;
 * other throwables are mapped to a typed [AppResult.Failure] via [ErrorMapper].
 * Wire [WireAppResult] values are converted to [AppResult] at this boundary.
 *
 * Pattern mirrors [ContributorRepositoryImpl]'s RPC fallback path from B2a-C.
 *
 * @property rpcFactory Supplies the [com.calypsan.listenup.api.MetadataLookupService]
 *   proxy, cached on first use with Mutex correctness.
 */
class MetadataRepositoryImpl(
    private val rpcFactory: MetadataLookupRpcFactory,
) : MetadataRepository {
    override suspend fun searchBooks(
        query: String,
        region: AudibleRegion?,
    ): AppResult<MetadataSearchResults> = wrap { rpcFactory.metadataLookupService().searchBooks(query, region) }

    override suspend fun getBookMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataBook?> = wrap { rpcFactory.metadataLookupService().getBookMetadata(asin, region) }

    override suspend fun getBookChapters(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataChapters?> = wrap { rpcFactory.metadataLookupService().getBookChapters(asin, region) }

    override suspend fun searchContributorMetadata(query: String): AppResult<List<MetadataContributorHit>> =
        wrap { rpcFactory.metadataLookupService().searchContributorMetadata(query) }

    override suspend fun getContributorMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataContributorProfile?> =
        wrap { rpcFactory.metadataLookupService().getContributorMetadata(asin, region) }

    override suspend fun refreshBookMetadata(
        asin: String,
        region: AudibleRegion,
    ): AppResult<MetadataBook?> = wrap { rpcFactory.metadataLookupService().refreshBookMetadata(asin, region) }

    override suspend fun applyBookMetadata(
        bookId: BookId,
        asin: String,
        region: AudibleRegion,
    ): AppResult<Unit> = wrap { rpcFactory.metadataLookupService().applyBookMetadata(bookId, asin, region) }

    override suspend fun applyContributorMetadata(
        contributorId: ContributorId,
        asin: String,
        region: AudibleRegion,
    ): AppResult<Unit> =
        wrap { rpcFactory.metadataLookupService().applyContributorMetadata(contributorId, asin, region) }

    /**
     * Wraps a RPC call, converting from the wire [WireAppResult] to the client [AppResult].
     * Re-throws [CancellationException]; all other throwables become [AppResult.Failure].
     */
    private suspend inline fun <T> wrap(block: () -> WireAppResult<T>): AppResult<T> =
        try {
            when (val result = block()) {
                is WireAppResult.Success -> AppResult.Success(result.data)
                is WireAppResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppResult.Failure(ErrorMapper.map(e))
        }
}
