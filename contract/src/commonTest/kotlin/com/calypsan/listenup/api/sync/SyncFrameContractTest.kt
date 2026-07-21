package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SyncFrameContractTest :
    FunSpec({
        test("SyncFrame round-trips domain, revision, and body") {
            val frame = SyncFrame(domain = "book", revision = 42L, json = """{"id":"b1"}""")
            val decoded = contractJson.decodeFromString<SyncFrame>(contractJson.encodeToString(frame))
            decoded shouldBe frame
        }

        test("control frame carries a null revision") {
            val frame = SyncFrame(domain = SyncFrame.CONTROL, json = """{"type":"Heartbeat"}""")
            val decoded = contractJson.decodeFromString<SyncFrame>(contractJson.encodeToString(frame))
            decoded.revision shouldBe null
            decoded.domain shouldBe SyncFrame.CONTROL
        }

        test("SyncControl.Heartbeat round-trips through the polymorphic hierarchy") {
            val original: SyncControl = SyncControl.Heartbeat
            val json = contractJson.encodeToString(SyncControl.serializer(), original)
            val decoded = contractJson.decodeFromString(SyncControl.serializer(), json)
            decoded shouldBe SyncControl.Heartbeat
        }
    })
