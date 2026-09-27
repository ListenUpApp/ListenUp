package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.HardcoverError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Pins the Hardcover connection wire shapes: every state and error round-trips with a stable discriminator. */
class HardcoverContractTest :
    FunSpec({

        val prompt =
            HardcoverLinkPrompt(
                userCode = "ABCD-1234",
                verificationUri = "https://hardcover.app/link",
                verificationUriComplete = "https://hardcover.app/link?code=ABCD-1234",
                expiresAt = 1_780_000_000_000L,
            )

        listOf<HardcoverConnection>(
            HardcoverConnection.NotOffered,
            HardcoverConnection.NotConnected(lastLinkFailure = null),
            HardcoverConnection.NotConnected(lastLinkFailure = HardcoverLinkFailure.DENIED),
            HardcoverConnection.Linking(prompt),
            HardcoverConnection.Connected(hardcoverUsername = "simon", since = 1_780_000_000_000L),
            HardcoverConnection.Broken(reason = HardcoverBrokenReason.REVOKED),
        ).forEach { state ->
            test("$state round-trips") {
                val json = contractJson.encodeToString(HardcoverConnection.serializer(), state)
                contractJson.decodeFromString(HardcoverConnection.serializer(), json) shouldBe state
            }
        }

        listOf<Pair<String, AppError>>(
            "HardcoverError.NotConfigured" to HardcoverError.NotConfigured(),
            "HardcoverError.Unavailable" to HardcoverError.Unavailable(),
            "HardcoverError.AlreadyConnected" to HardcoverError.AlreadyConnected(),
        ).forEach { (discriminator, error) ->
            test("$discriminator round-trips through AppError") {
                val json = contractJson.encodeToString(AppError.serializer(), error)
                json.contains("\"$discriminator\"") shouldBe true
                contractJson.decodeFromString(AppError.serializer(), json) shouldBe error
            }
        }

        test("only Unavailable is retryable") {
            HardcoverError.Unavailable().isRetryable shouldBe true
            HardcoverError.NotConfigured().isRetryable shouldBe false
            HardcoverError.AlreadyConnected().isRetryable shouldBe false
        }
    })
