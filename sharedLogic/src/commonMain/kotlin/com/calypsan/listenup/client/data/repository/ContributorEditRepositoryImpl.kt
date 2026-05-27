package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.remote.ContributorRpcFactory
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.error.ErrorMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Pure RPC dispatcher for contributor edits.
 *
 * No optimistic Room writes — the SSE echo from the server is the single write
 * path back into Room. This keeps state consistent across devices and matches
 * the [BookEditRepositoryImpl] / [TagRepositoryImpl] write pattern.
 *
 * Wire [WireAppResult] values returned by the RPC service are converted to the
 * client-layer [AppResult] at this boundary.
 */
class ContributorEditRepositoryImpl(
    private val contributorRpcFactory: ContributorRpcFactory,
) : ContributorEditRepository {
    override suspend fun updateContributor(
        id: ContributorId,
        patch: ContributorUpdate,
    ): AppResult<Unit> = rpcCallUnit { contributorRpcFactory.contributorService().updateContributor(id, patch) }

    override suspend fun deleteContributor(id: ContributorId): AppResult<Unit> =
        rpcCallUnit { contributorRpcFactory.contributorService().deleteContributor(id) }

    /**
     * Run an RPC call that returns [Unit], converting [WireAppResult] → [AppResult].
     * Re-throws [CancellationException]; all other throwables become [AppResult.Failure]
     * via [ErrorMapper].
     */
    private suspend fun rpcCallUnit(block: suspend () -> WireAppResult<Unit>): AppResult<Unit> =
        try {
            when (val result = block()) {
                is WireAppResult.Success -> AppResult.Success(Unit)
                is WireAppResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Contributor edit RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }
}
