package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString

class AuthDtoContractTest :
    FunSpec({
        test("SessionSummary round-trips with nested DeviceInfo and omits null device info") {
            val full =
                SessionSummary(
                    id = SessionId("s1"),
                    label = "My iPhone",
                    deviceInfo =
                        DeviceInfo(
                            deviceType = "phone",
                            platform = "iOS",
                            platformVersion = "17.2",
                            clientName = "ListenUp iOS",
                            clientVersion = "1.0.0",
                            deviceName = "Simon's iPhone",
                            deviceModel = "iPhone15,2",
                        ),
                    userAgent = "ListenUp/1.0",
                    createdAt = 1L,
                    lastUsedAt = 2L,
                    current = true,
                )
            contractJson.decodeFromString<SessionSummary>(contractJson.encodeToString(full)) shouldBe full

            val minimal =
                SessionSummary(
                    id = SessionId("s2"),
                    label = null,
                    createdAt = 1L,
                    lastUsedAt = 2L,
                    current = false,
                )
            contractJson.encodeToString(minimal).shouldNotContain("deviceInfo")
            contractJson.decodeFromString<SessionSummary>(contractJson.encodeToString(minimal)) shouldBe minimal
        }

        test("LoginRequest carries optional deviceInfo") {
            val req =
                LoginRequest(
                    email = "a@b.co",
                    password = "password1",
                    deviceInfo = DeviceInfo(deviceModel = "Pixel 10"),
                )
            contractJson.decodeFromString<LoginRequest>(contractJson.encodeToString(req)) shouldBe req
        }
    })
