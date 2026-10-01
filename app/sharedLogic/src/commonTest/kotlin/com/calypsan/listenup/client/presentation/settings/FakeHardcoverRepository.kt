package com.calypsan.listenup.client.presentation.settings

import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.HardcoverRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * In-memory [HardcoverRepository]: [connection] is the server's stream (null = no answer yet),
 * the results are scripted, and a non-null gate holds a call in flight until it is completed.
 */
internal class FakeHardcoverRepository(
    initial: HardcoverConnection? = null,
) : HardcoverRepository {
    val connection = MutableStateFlow(initial)

    var startLinkResult: AppResult<HardcoverLinkPrompt> = AppResult.Success(SAMPLE_PROMPT)
    var disconnectResult: AppResult<Unit> = AppResult.Success(Unit)
    var startLinkGate: CompletableDeferred<Unit>? = null
    var disconnectGate: CompletableDeferred<Unit>? = null

    var startLinkCalls = 0
        private set
    var disconnectCalls = 0
        private set

    var syncIfStaleCalls = 0
        private set

    override suspend fun syncIfStale(): AppResult<Unit> {
        syncIfStaleCalls++
        return AppResult.Success(Unit)
    }

    override fun observeConnection(): Flow<HardcoverConnection> = connection.filterNotNull()

    override suspend fun startLink(): AppResult<HardcoverLinkPrompt> {
        startLinkCalls++
        startLinkGate?.await()
        return startLinkResult
    }

    override suspend fun disconnect(): AppResult<Unit> {
        disconnectCalls++
        disconnectGate?.await()
        return disconnectResult
    }

    companion object {
        val SAMPLE_PROMPT =
            HardcoverLinkPrompt(
                userCode = "ABCD-1234",
                verificationUri = "https://hardcover.app/link",
                verificationUriComplete = "https://hardcover.app/link?code=ABCD-1234",
                expiresAt = 1_800_000_000_000L,
            )
    }
}
