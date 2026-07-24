package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins that [ServerConnectError.LocalNetworkPermissionDenied] round-trips through
 * the canonical [contractJson] using the polymorphic `AppError.serializer()` and
 * has a stable `@SerialName`, plus the body-level constants the UI consumes.
 */
class ServerConnectErrorContractTest :
    FunSpec({
        test("LocalNetworkPermissionDenied round-trips through JSON") {
            val original: AppError =
                ServerConnectError.LocalNetworkPermissionDenied(
                    debugInfo = "user denied at runtime",
                )
            val encoded = contractJson.encodeToString(AppError.serializer(), original)
            val decoded = contractJson.decodeFromString(AppError.serializer(), encoded)
            decoded shouldBe original
        }

        test("LocalNetworkPermissionDenied has stable @SerialName") {
            val err: AppError = ServerConnectError.LocalNetworkPermissionDenied()
            val json = contractJson.encodeToString(AppError.serializer(), err)
            json.contains("\"type\":\"ServerConnectError.LocalNetworkPermissionDenied\"") shouldBe true
        }

        test("LocalNetworkPermissionDenied carries the expected message and code") {
            val error = ServerConnectError.LocalNetworkPermissionDenied()
            error.message shouldBe "Local network access is required to discover servers on your network."
            error.code shouldBe "SERVER_CONNECT_LOCAL_NETWORK_PERMISSION_DENIED"
            error.isRetryable shouldBe false
        }
    })
