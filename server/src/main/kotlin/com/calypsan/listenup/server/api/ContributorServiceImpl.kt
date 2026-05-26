package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository

/**
 * Thin [ContributorService] implementation.
 *
 * Translates read requests and user-edit mutations for contributor entities from
 * the wire contract to repository calls.
 *
 * Mutation methods ([updateContributor], [deleteContributor]) are stub
 * implementations returning [ContributorError.NotFound] until Task 17 replaces
 * them with real logic.
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

    override suspend fun updateContributor(
        id: ContributorId,
        patch: ContributorUpdate,
    ): AppResult<Unit> =
        AppResult.Failure(
            ContributorError.NotFound(debugInfo = "updateContributor not yet implemented (Books-C1 Task 17)"),
        )

    override suspend fun deleteContributor(id: ContributorId): AppResult<Unit> =
        AppResult.Failure(
            ContributorError.NotFound(debugInfo = "deleteContributor not yet implemented (Books-C1 Task 17)"),
        )
}
