package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.InternalError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SyncControlContractTest :
    FunSpec({

        test("CursorStale round-trips") {
            val original: SyncControl = SyncControl.CursorStale(lastKnownRevision = 999)
            val json = contractJson.encodeToString(SyncControl.serializer(), original)
            val decoded = contractJson.decodeFromString(SyncControl.serializer(), json)
            decoded shouldBe original
        }

        test("StreamError round-trips with typed AppError payload") {
            val err = InternalError(correlationId = "cid-xyz", cause = "NPE")
            val original: SyncControl = SyncControl.StreamError(error = err)
            val json = contractJson.encodeToString(SyncControl.serializer(), original)
            val decoded = contractJson.decodeFromString(SyncControl.serializer(), json)
            decoded.shouldBeInstanceOf<SyncControl.StreamError>()
            (decoded as SyncControl.StreamError).error.shouldBeInstanceOf<InternalError>()
            (decoded.error as InternalError).correlationId shouldBe "cid-xyz"
        }

        test("AccessChanged round-trips") {
            val original: SyncControl = SyncControl.AccessChanged
            val json = contractJson.encodeToString(SyncControl.serializer(), original)
            val decoded = contractJson.decodeFromString(SyncControl.serializer(), json)
            decoded shouldBe SyncControl.AccessChanged
        }

        test("Stable discriminators") {
            val stale: SyncControl = SyncControl.CursorStale(0)
            val streamErr: SyncControl = SyncControl.StreamError(InternalError())
            val accessChanged: SyncControl = SyncControl.AccessChanged
            contractJson
                .encodeToString(SyncControl.serializer(), stale)
                .contains("\"type\":\"SyncControl.CursorStale\"") shouldBe true
            contractJson
                .encodeToString(SyncControl.serializer(), streamErr)
                .contains("\"type\":\"SyncControl.StreamError\"") shouldBe true
            contractJson
                .encodeToString(SyncControl.serializer(), accessChanged)
                .contains("\"type\":\"SyncControl.AccessChanged\"") shouldBe true
        }
    })
