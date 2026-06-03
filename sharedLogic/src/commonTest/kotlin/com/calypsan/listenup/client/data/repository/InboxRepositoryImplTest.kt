package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.client.data.remote.CollectionInboxApiContract
import com.calypsan.listenup.api.result.AppResult
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for [InboxRepositoryImpl] over the 1b admin inbox REST surface.
 *
 * The repository is a thin pass-through to [CollectionInboxApiContract]; these tests
 * pin the delegation (and the assignment-map forwarding) against a faked API.
 */
class InboxRepositoryImplTest :
    FunSpec({

        test("listInbox forwards to the API and returns the mapped book ids") {
            runTest {
                val api: CollectionInboxApiContract = mock()
                everySuspend { api.listInbox("lib1") } returns AppResult.Success(listOf("b1", "b2"))
                val result = InboxRepositoryImpl(api).listInbox("lib1")
                val success = result.shouldBeInstanceOf<AppResult.Success<List<String>>>()
                success.data shouldBe listOf("b1", "b2")
            }
        }

        test("releaseBooks forwards the per-book assignment map verbatim") {
            runTest {
                val api: CollectionInboxApiContract = mock()
                val assignments = mapOf("b1" to listOf("col1"), "b2" to emptyList())
                everySuspend { api.releaseBooks("lib1", assignments) } returns AppResult.Success(Unit)

                InboxRepositoryImpl(api).releaseBooks("lib1", assignments).shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { api.releaseBooks("lib1", assignments) }
            }
        }

        test("listInbox propagates a failure") {
            runTest {
                val api: CollectionInboxApiContract = mock()
                everySuspend { api.listInbox("lib1") } returns AppResult.Failure(ValidationError(message = "forbidden"))
                InboxRepositoryImpl(api).listInbox("lib1").shouldBeInstanceOf<AppResult.Failure>()
            }
        }
    })
