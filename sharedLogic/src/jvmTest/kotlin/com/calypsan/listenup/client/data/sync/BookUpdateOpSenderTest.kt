package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class BookUpdateOpSenderTest :
    FunSpec({

        test("decodes BookUpdate and calls updateBook; returns Success on RPC success") {
            runTest {
                val patch = BookUpdate(title = "New Title")
                val payload = contractJson.encodeToString(BookUpdate.serializer(), patch)

                val service: BookService = mock()
                everySuspend { service.updateBook(any(), any()) } returns WireAppResult.Success(Unit)

                val rpcFactory: BookRpcFactory = mock()
                everySuspend { rpcFactory.bookService() } returns service

                val result = BookUpdateOpSender(rpcFactory).send(fakeOp(payload))

                result shouldBe AppResult.Success(Unit)
                verifySuspend(exactly(1)) { service.updateBook(BookId("book1"), patch) }
            }
        }

        test("propagates a WireAppResult.Failure as AppResult.Failure") {
            runTest {
                val patch = BookUpdate(title = "New Title")
                val payload = contractJson.encodeToString(BookUpdate.serializer(), patch)

                val expectedError = SyncError.PushFailed()
                val service: BookService = mock()
                everySuspend { service.updateBook(any(), any()) } returns WireAppResult.Failure(expectedError)

                val rpcFactory: BookRpcFactory = mock()
                everySuspend { rpcFactory.bookService() } returns service

                val result = BookUpdateOpSender(rpcFactory).send(fakeOp(payload))

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error shouldBe expectedError
            }
        }
    })

private fun fakeOp(payload: String): PendingOperation =
    PendingOperation(
        clientOpId = "op-1",
        domainName = "books",
        entityId = "book1",
        opType = "update",
        payload = payload,
        enqueuedAt = 1_000L,
        failureCount = 0,
        ownerUserId = "user-1",
    )
