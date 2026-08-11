package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ServerInfoContractTest :
    FunSpec({
        test("ServerInfo round-trips with a remoteUrl") {
            val info =
                ServerInfo(
                    name = "ListenUp",
                    version = "0.0.1",
                    apiVersion = "v1",
                    setupRequired = false,
                    registrationPolicy = RegistrationPolicy.OPEN,
                    remoteUrl = "https://library.example.com",
                    instanceId = "test-instance",
                )
            contractJson.decodeFromString<ServerInfo>(contractJson.encodeToString(info)) shouldBe info
        }

        test("ServerInfo round-trips with a null remoteUrl (unset)") {
            val info =
                ServerInfo(
                    name = "ListenUp",
                    version = "0.0.1",
                    apiVersion = "v1",
                    setupRequired = true,
                    registrationPolicy = RegistrationPolicy.CLOSED,
                    remoteUrl = null,
                    instanceId = "test-instance",
                )
            contractJson.decodeFromString<ServerInfo>(contractJson.encodeToString(info)) shouldBe info
        }

        test("ServerInfo round-trips instanceId") {
            val original =
                ServerInfo(
                    name = "ListenUp",
                    version = "0.0.1",
                    apiVersion = "v1",
                    setupRequired = false,
                    registrationPolicy = RegistrationPolicy.CLOSED,
                    remoteUrl = null,
                    instanceId = "inst-123",
                )
            val decoded = contractJson.decodeFromString<ServerInfo>(contractJson.encodeToString(original))
            decoded.instanceId shouldBe "inst-123"
            decoded shouldBe original
        }

        test("ServerInfo without pushEnabled field deserializes to false (backward-compat)") {
            val info =
                ServerInfo(
                    name = "ListenUp",
                    version = "0.0.1",
                    apiVersion = "v1",
                    setupRequired = false,
                    registrationPolicy = RegistrationPolicy.OPEN,
                    remoteUrl = null,
                    instanceId = "test-instance",
                    pushEnabled = true,
                )
            val full =
                contractJson
                    .parseToJsonElement(
                        contractJson.encodeToString(info),
                    ).jsonObject
            val withoutPushEnabled = JsonObject(full.toMutableMap().apply { remove("pushEnabled") })
            val strippedJson = contractJson.encodeToString(withoutPushEnabled)
            val decoded = contractJson.decodeFromString<ServerInfo>(strippedJson)
            decoded.pushEnabled shouldBe false
        }
    })
