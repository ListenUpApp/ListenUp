package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class RpcUpdateOpSenderTest :
    FunSpec({

        test("decodes the patch with the domain serializer, invokes push, returns Success") {
            runTest {
                val patch = BookUpdate(title = "New Title")
                val payload = contractJson.encodeToString(BookUpdate.serializer(), patch)

                var seenEntityId: String? = null
                var seenPatch: BookUpdate? = null
                val sender =
                    RpcUpdateOpSender(BookEdit) { entityId, decoded ->
                        seenEntityId = entityId
                        seenPatch = decoded
                        WireAppResult.Success(Unit)
                    }

                val result = sender.send(fakeOp(payload))

                result shouldBe AppResult.Success(Unit)
                seenEntityId shouldBe "book1"
                seenPatch shouldBe patch
            }
        }

        test("discards a non-Unit push payload and still returns Success") {
            runTest {
                val patch = BookUpdate(title = "New Title")
                val payload = contractJson.encodeToString(BookUpdate.serializer(), patch)

                // Profile/Preferences update RPCs return a value, not Unit — the sender discards it.
                val sender = RpcUpdateOpSender(BookEdit) { _, _ -> WireAppResult.Success("ignored") }

                sender.send(fakeOp(payload)) shouldBe AppResult.Success(Unit)
            }
        }

        test("propagates a WireAppResult.Failure as AppResult.Failure") {
            runTest {
                val patch = BookUpdate(title = "New Title")
                val payload = contractJson.encodeToString(BookUpdate.serializer(), patch)
                val expectedError = SyncError.PushFailed()

                val sender = RpcUpdateOpSender(BookEdit) { _, _ -> WireAppResult.Failure(expectedError) }

                val failure = sender.send(fakeOp(payload)).shouldBeInstanceOf<AppResult.Failure>()
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
