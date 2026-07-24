@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Dao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.ContributorId
import dev.mokkery.answering.calls
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * Tests for [ContributorEditRepositoryImpl]'s never-stranded guards.
 *
 * A contributor merge does O(books) work server-side and has been seen to hang indefinitely under
 * real-transport conditions (the RPC response never arrives), leaving the edit screen spinning
 * forever. The repository caps the wait so the UI gets an honest, non-retryable OutcomeUnknown error
 * instead — the merge frame was sent, so it may have committed and must NOT be blindly retried.
 */
class ContributorEditRepositoryImplTest :
    FunSpec({
        test("mergeContributor surfaces a non-retryable OutcomeUnknown failure when the RPC never returns") {
            runTest {
                // A merge RPC that never completes (far longer than the channel's 30s cap).
                val service =
                    mock<ContributorService> {
                        everySuspend { mergeContributors(any(), any()) } calls {
                            delay(10 * 60_000L)
                            AppResult.Success(Unit)
                        }
                    }

                val repo =
                    ContributorEditRepositoryImpl(
                        channel = RpcChannel.forTest(service),
                        contributorDao = mock<ContributorDao>(),
                        offlineEditor =
                            OfflineEditor(
                                pendingQueue =
                                    PendingOperationQueue(
                                        dao = mock<PendingOperationV2Dao>(),
                                        sender = PendingOperationSender { AppResult.Success(Unit) },
                                    ),
                                transactionRunner =
                                    object : TransactionRunner {
                                        override suspend fun <R> atomically(block: suspend () -> R): R = block()
                                    },
                                authSession = FakeAuthSession(userId = "u1"),
                            ),
                    )
                val result =
                    repo.mergeContributor(ContributorId("source"), ContributorId("target"))

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<TransportError.OutcomeUnknown>()
            }
        }
    })
