package com.calypsan.listenup.client.data.remote

import io.ktor.client.plugins.websocket.WebSocketException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.IOException

class RpcFailureClassifierTest :
    FunSpec({
        test("a 401 WebSocket handshake is recognized as auth-recoverable") {
            val e = WebSocketException("Handshake exception, expected status code 101 but was 401")
            RpcFailureClassifier.isWsHandshake401(e) shouldBe true
        }

        test("a non-401 WebSocket handshake is a pre-delivery transport failure, not an auth one") {
            val e = WebSocketException("Handshake exception, expected status code 101 but was 500")
            RpcFailureClassifier.isWsHandshake401(e) shouldBe false
            RpcFailureClassifier.isPreDeliveryTransportFailure(e) shouldBe true
        }

        test("any WebSocket handshake failure is pre-delivery — the frame never sent, so retry is safe") {
            RpcFailureClassifier.isPreDeliveryTransportFailure(
                WebSocketException("Handshake exception, expected status code 101 but was 401"),
            ) shouldBe true
        }

        test("a failure that could have reached a handler is NOT pre-delivery (no auto-retry)") {
            RpcFailureClassifier.isPreDeliveryTransportFailure(IOException("connection reset mid-stream")) shouldBe false
            RpcFailureClassifier.isPreDeliveryTransportFailure(RuntimeException("boom")) shouldBe false
            RpcFailureClassifier.isWsHandshake401(RuntimeException("nope")) shouldBe false
        }
    })
