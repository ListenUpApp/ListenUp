package com.calypsan.listenup.core

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

class TimestampSerializationTest :
    FunSpec({
        test("Timestamp serializes as an ISO-8601 string") {
            val json = contractJson.encodeToString(TimestampHolder(Timestamp(0L)))
            json shouldBe """{"at":"1970-01-01T00:00:00Z"}"""
        }

        test("Timestamp decodes from an ISO-8601 string") {
            val decoded = contractJson.decodeFromString<TimestampHolder>("""{"at":"1970-01-01T00:00:00Z"}""")
            decoded.at shouldBe Timestamp(0L)
        }

        test("Timestamp round-trips an arbitrary epoch-millis value through JSON") {
            val ts = Timestamp(1_732_113_045_123L)
            val decoded =
                contractJson.decodeFromString<TimestampHolder>(
                    contractJson.encodeToString(TimestampHolder(ts)),
                )
            decoded.at shouldBe ts
        }
    })

@Serializable
private data class TimestampHolder(
    val at: Timestamp,
)
