package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServerConnectErrorTest :
    FunSpec({
        test("InvalidUrl has stable code and is not auto-retryable") {
            val err: AppError = ServerConnectError.InvalidUrl(reason = "blank")
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "SERVER_CONNECT_INVALID_URL_BLANK"
            err.isRetryable shouldBe false
        }

        test("InvalidUrl carries the reason payload") {
            val err = ServerConnectError.InvalidUrl(reason = "malformed")
            err.reason shouldBe "malformed"
            err.code shouldBe "SERVER_CONNECT_INVALID_URL_MALFORMED"
            err.message.isNotBlank() shouldBe true
        }

        test("NotListenUpServer has stable code and is not auto-retryable") {
            val err: AppError = ServerConnectError.NotListenUpServer()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "SERVER_CONNECT_NOT_LISTENUP_SERVER"
            err.isRetryable shouldBe false
        }

        test("ServerNotReachable has stable code and is auto-retryable") {
            val err: AppError = ServerConnectError.ServerNotReachable()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "SERVER_CONNECT_NOT_REACHABLE"
            err.isRetryable shouldBe true
        }

        test("VerificationFailed has stable code and is auto-retryable") {
            val err: AppError = ServerConnectError.VerificationFailed()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "SERVER_CONNECT_VERIFICATION_FAILED"
            err.isRetryable shouldBe true
        }

        test("LocalNetworkPermissionDenied names both finding and connecting, and is not auto-retryable") {
            // The same denial blocks a hand-typed LAN address, not only discovery, so the copy can't
            // promise that manual entry is the way around it.
            val err: AppError = ServerConnectError.LocalNetworkPermissionDenied()
            err.message shouldBe "ListenUp needs local network access to find and connect to servers on your network."
            err.code shouldBe "SERVER_CONNECT_LOCAL_NETWORK_PERMISSION_DENIED"
            err.isRetryable shouldBe false
        }
    })
