package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SyncErrorTest :
    FunSpec({
        test("SyncFailed has stable code and is auto-retryable") {
            val err: AppError = SyncError.SyncFailed()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "SYNC_FAILED"
            err.isRetryable shouldBe true
        }

        test("RealtimeDisconnected has stable code and is auto-retryable") {
            val err = SyncError.RealtimeDisconnected()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "SYNC_REALTIME_DISCONNECTED"
            err.isRetryable shouldBe true
        }

        test("PushFailed has stable code and is auto-retryable") {
            val err = SyncError.PushFailed()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "SYNC_PUSH_FAILED"
            err.isRetryable shouldBe true
        }

        test("NotFound has stable code, is not retryable, and carries domain + entityId") {
            val err = SyncError.NotFound(domain = "tags", entityId = "abc-123")
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "SYNC_NOT_FOUND"
            err.isRetryable shouldBe false
            err.domain shouldBe "tags"
            err.entityId shouldBe "abc-123"
        }
    })
