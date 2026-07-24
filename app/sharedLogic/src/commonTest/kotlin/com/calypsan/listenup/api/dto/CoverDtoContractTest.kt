package com.calypsan.listenup.api.dto

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CoverDtoContractTest :
    FunSpec({
        test("CoverSearchResults round-trips through contractJson") {
            val original =
                CoverSearchResults(
                    options =
                        listOf(
                            CoverOption(CoverOptionSource.AUDIBLE, "https://a/c.jpg", 500, 500, "B01ASIN"),
                            CoverOption(CoverOptionSource.ITUNES, "https://i/7000x7000bb.jpg", 7000, 7000, "12345"),
                        ),
                )
            val encoded = contractJson.encodeToString(CoverSearchResults.serializer(), original)
            val decoded = contractJson.decodeFromString(CoverSearchResults.serializer(), encoded)
            decoded shouldBe original
        }
    })
