package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository

/**
 * Thin [ContributorService] implementation.
 *
 * Translates cache-miss read requests for contributor entities from the wire
 * contract to repository calls. The implementation is read-only: all writes
 * go through the scanner via [ContributorRepository.resolveOrCreate].
 *
 * This service is not user-scoped — it carries no [com.calypsan.listenup.server.auth.PrincipalProvider]
 * because contributor reads are not per-user. Auth is enforced at the route
 * layer (JWT gate in Application.kt).
 */
internal class ContributorServiceImpl(
    private val contributorRepo: ContributorRepository,
    private val bookRepo: BookRepository,
) : ContributorService {
    override suspend fun getContributor(id: ContributorId): AppResult<ContributorSyncPayload?> =
        AppResult.Success(contributorRepo.findById(id.value))

    override suspend fun listBooksByContributor(id: ContributorId): AppResult<List<BookSyncPayload>> =
        AppResult.Success(bookRepo.findByContributor(id))
}
