package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.client.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

// InstanceId is declared in Instance.kt — same package, so no import is needed.
class InstanceSerializationTest :
    FunSpec({
        test("Instance timestamps round-trip through JSON") {
            val instance =
                Instance(
                    id = InstanceId("inst-1"),
                    name = "Home",
                    version = "1.0.0",
                    setupRequired = false,
                    createdAt = Timestamp(1_732_113_045_000L),
                    updatedAt = Timestamp(1_732_114_800_000L),
                )
            val decoded =
                contractJson.decodeFromString(
                    Instance.serializer(),
                    contractJson.encodeToString(Instance.serializer(), instance),
                )
            decoded.createdAt shouldBe instance.createdAt
            decoded.updatedAt shouldBe instance.updatedAt
        }

        test("Instance decodes ISO-8601 timestamp strings and re-encodes them unchanged") {
            val wireJson =
                """{"id":"inst-1","name":"Home","version":"1.0.0","setup_required":false,""" +
                    """"created_at":"2024-11-20T14:30:45Z","updated_at":"2024-11-20T15:00:00Z"}"""
            val instance = contractJson.decodeFromString(Instance.serializer(), wireJson)
            val reencoded = contractJson.encodeToString(Instance.serializer(), instance)
            (""""created_at":"2024-11-20T14:30:45Z"""" in reencoded) shouldBe true
            (""""updated_at":"2024-11-20T15:00:00Z"""" in reencoded) shouldBe true
        }
    })
