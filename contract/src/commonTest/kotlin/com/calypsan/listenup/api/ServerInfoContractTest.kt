package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

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
                )
            contractJson.decodeFromString<ServerInfo>(contractJson.encodeToString(info)) shouldBe info
        }
    })
