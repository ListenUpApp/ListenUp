package com.calypsan.listenup.client.data.remote

import io.ktor.client.plugins.websocket.WebSocketException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException

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

        test("a dead RpcClient is recognized ONLY as an IllegalStateException, never a CancellationException") {
            // Real kotlinx.rpc surfaces a torn-down client as a plain IllegalStateException thrown
            // BEFORE send — pre-delivery, so a retry can't double-apply.
            RpcFailureClassifier.isDeadRpcClient(IllegalStateException("RpcClient was cancelled")) shouldBe true
            // A CancellationException carrying the same message is a POST-delivery cancellation (the
            // pending, already-sent request channel closed). Classifying it as a dead client would
            // license a retry that double-applies a possibly-committed mutation — it must be false.
            RpcFailureClassifier.isDeadRpcClient(CancellationException("RpcClient was cancelled")) shouldBe false
            // Only an IllegalStateException qualifies — a bare Throwable with the message does not.
            RpcFailureClassifier.isDeadRpcClient(RuntimeException("RpcClient was cancelled")) shouldBe false
            RpcFailureClassifier.isDeadRpcClient(IllegalStateException("something else entirely")) shouldBe false
            RpcFailureClassifier.isDeadRpcClient(RuntimeException()) shouldBe false
        }
    })
