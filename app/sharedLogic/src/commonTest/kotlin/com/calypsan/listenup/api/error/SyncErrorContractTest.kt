package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins that [SyncError.NotFound] round-trips through the canonical [contractJson]
 * using the polymorphic `AppError.serializer()` and has a stable `@SerialName`.
 */
class SyncErrorContractTest :
    FunSpec({
        test("NotFound round-trips") {
            val original: AppError = SyncError.NotFound(domain = "tags", entityId = "missing")
            val json = contractJson.encodeToString(AppError.serializer(), original)
            val decoded = contractJson.decodeFromString(AppError.serializer(), json)
            decoded shouldBe original
        }

        test("NotFound has stable @SerialName") {
            val err: AppError = SyncError.NotFound(domain = "tags", entityId = "x")
            val json = contractJson.encodeToString(AppError.serializer(), err)
            json.contains("\"type\":\"SyncError.NotFound\"") shouldBe true
        }
    })
