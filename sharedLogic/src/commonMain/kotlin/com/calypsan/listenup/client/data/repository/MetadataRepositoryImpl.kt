package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.dto.MetadataContributorProfile
import com.calypsan.listenup.api.dto.MetadataSearchResults
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.MetadataRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId

/**
 * Repository implementation backing [MetadataRepository] against the
 * [com.calypsan.listenup.api.MetadataLookupService] RPC contract.
 *
 * Each method dispatches through the bounded, self-healing [RpcChannel] for
 * [MetadataLookupService], which folds the RPC outcome into an [AppResult] (a
 * transport throw becomes a typed `Failure`; a business `Failure` returned by the
 * service passes through untouched) and re-raises `CancellationException`.
 *
 * Pattern mirrors [ContributorRepositoryImpl]'s RPC fallback path from B2a-C.
 *
 * @property channel [RpcChannel] over [com.calypsan.listenup.api.MetadataLookupService]
 *   that every lookup dispatches through.
 */
internal class MetadataRepositoryImpl(
    private val channel: RpcChannel<MetadataLookupService>,
) : MetadataRepository {
    override suspend fun searchBooks(
        query: String,
        region: MetadataLocale?,
    ): AppResult<MetadataSearchResults> = channel.call(idempotent = true) { it.searchBooks(query, region) }

    override suspend fun getBookMetadata(
        asin: String,
        region: MetadataLocale,
    ): AppResult<MetadataBook?> = channel.call(idempotent = true) { it.getBookMetadata(asin, region) }

    override suspend fun getBookChapters(
        asin: String,
        region: MetadataLocale,
    ): AppResult<MetadataChapters?> = channel.call(idempotent = true) { it.getBookChapters(asin, region) }

    override suspend fun searchContributorMetadata(query: String): AppResult<List<MetadataContributorHit>> =
        channel.call(idempotent = true) { it.searchContributorMetadata(query) }

    override suspend fun getContributorMetadata(
        asin: String,
        region: MetadataLocale,
    ): AppResult<MetadataContributorProfile?> =
        channel.call(idempotent = true) { it.getContributorMetadata(asin, region) }

    override suspend fun refreshBookMetadata(
        asin: String,
        region: MetadataLocale,
    ): AppResult<MetadataBook?> = channel.call { it.refreshBookMetadata(asin, region) }

    override suspend fun applyBookMetadata(
        bookId: BookId,
        asin: String,
        region: MetadataLocale,
        selection: MetadataApplySelection,
    ): AppResult<Unit> = channel.call { it.applyBookMetadata(bookId, asin, region, selection) }

    override suspend fun applyChapterNames(
        bookId: BookId,
        asin: String,
        region: MetadataLocale,
        ordinals: Set<Int>,
    ): AppResult<Unit> = channel.call { it.applyChapterNames(bookId, asin, region, ordinals) }

    override suspend fun applyContributorMetadata(
        contributorId: ContributorId,
        asin: String,
        region: MetadataLocale,
    ): AppResult<Unit> = channel.call { it.applyContributorMetadata(contributorId, asin, region) }
}
