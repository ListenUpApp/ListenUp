package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString

class TransportErrorSerializationTest :
    FunSpec({
        test("ContractMismatch round-trips through the contract JSON as a typed AppError") {
            val original: AppError =
                TransportError.ContractMismatch(
                    correlationId = "c",
                    detail = "x",
                )

            val json = contractJson.encodeToString(original)
            val decoded = contractJson.decodeFromString<AppError>(json)

            decoded.shouldBeInstanceOf<TransportError.ContractMismatch>()
            decoded shouldBe original
            decoded.code shouldBe "TRANSPORT_CONTRACT_MISMATCH"
            decoded.isRetryable shouldBe false
            decoded.message shouldBe "The app and server versions don't match."
        }
    })
