package com.calypsan.listenup.api.dto.admin

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

class AdminServerSettingsTest :
    FunSpec({
        test("AdminServerSettings round-trips (incl. null remoteUrl)") {
            val v =
                AdminServerSettings(
                    serverName = "My Library",
                    remoteUrl = null,
                    inboxEnabled = false,
                    pushNotificationsEnabled = true,
                )
            contractJson.decodeFromString<AdminServerSettings>(contractJson.encodeToString(v)) shouldBe v
        }
        test("AdminServerSettingsPatch round-trips (defaults null = unchanged)") {
            val v = AdminServerSettingsPatch(serverName = "X", remoteUrl = "", pushNotificationsEnabled = false)
            contractJson.decodeFromString<AdminServerSettingsPatch>(contractJson.encodeToString(v)) shouldBe v
        }
    })
