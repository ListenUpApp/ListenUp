@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.ContributorRpcFactory
import com.calypsan.listenup.core.ContributorId
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
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
 * forever. The repository caps the wait so the UI gets a retryable error instead.
 */
class ContributorEditRepositoryImplTest :
    FunSpec({
        test("mergeContributor surfaces a retryable Timeout failure when the RPC never returns") {
            runTest {
                // A merge RPC that never completes (far longer than the repository's internal cap).
                val service =
                    mock<ContributorService> {
                        everySuspend { mergeContributors(any(), any()) } calls {
                            delay(10 * 60_000L)
                            AppResult.Success(Unit)
                        }
                    }
                val factory =
                    mock<ContributorRpcFactory> {
                        everySuspend { contributorService() } returns service
                    }

                val result =
                    ContributorEditRepositoryImpl(factory)
                        .mergeContributor(ContributorId("source"), ContributorId("target"))

                result
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<TransportError.Timeout>()
            }
        }
    })
