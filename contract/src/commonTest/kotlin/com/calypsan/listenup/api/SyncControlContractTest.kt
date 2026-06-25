package com.calypsan.listenup.api

import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SyncControlContractTest :
    FunSpec({
        test("ServerInfoChanged round-trips on the polymorphic SyncControl surface") {
            val original: SyncControl = SyncControl.ServerInfoChanged
            val json = contractJson.encodeToString(SyncControl.serializer(), original)
            val decoded = contractJson.decodeFromString(SyncControl.serializer(), json)
            decoded shouldBe SyncControl.ServerInfoChanged
        }

        test("ServerInfoChanged encodes its stable discriminator") {
            val json = contractJson.encodeToString(SyncControl.serializer(), SyncControl.ServerInfoChanged)
            json.contains("SyncControl.ServerInfoChanged") shouldBe true
        }

        test("PreferencesChanged round-trips on the polymorphic SyncControl surface") {
            val original: SyncControl = SyncControl.PreferencesChanged
            val json = contractJson.encodeToString(SyncControl.serializer(), original)
            val decoded = contractJson.decodeFromString(SyncControl.serializer(), json)
            decoded shouldBe SyncControl.PreferencesChanged
        }

        test("PreferencesChanged encodes its stable discriminator") {
            val json = contractJson.encodeToString(SyncControl.serializer(), SyncControl.PreferencesChanged)
            json.contains("SyncControl.PreferencesChanged") shouldBe true
        }
    })
