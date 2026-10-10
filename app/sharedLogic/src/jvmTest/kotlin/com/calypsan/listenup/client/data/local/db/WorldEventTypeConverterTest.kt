package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.api.sync.WorldEventType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WorldEventTypeConverterTest :
    FunSpec({
        val converter = WorldEventTypeConverter()

        test("types store by name and read back") {
            WorldEventType.entries.forEach { converter.toWorldEventType(converter.fromWorldEventType(it)) shouldBe it }
        }

        test("a type written by a newer build reads as UNKNOWN instead of failing the query") {
            converter.toWorldEventType("MARRIES") shouldBe WorldEventType.UNKNOWN
        }
    })
