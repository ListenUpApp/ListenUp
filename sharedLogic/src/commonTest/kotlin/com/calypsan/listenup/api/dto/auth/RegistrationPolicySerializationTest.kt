package com.calypsan.listenup.api.dto.auth

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RegistrationPolicySerializationTest :
    FunSpec({
        test("RegistrationPolicy round-trips every value through JSON") {
            RegistrationPolicy.entries.forEach { policy ->
                val decoded =
                    contractJson.decodeFromString<RegistrationPolicy>(
                        contractJson.encodeToString(policy),
                    )
                decoded shouldBe policy
            }
        }
    })
