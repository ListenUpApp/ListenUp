package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.core.WorldEventId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WorldEventEnumsContractTest :
    FunSpec({
        test("every known type round-trips by name") {
            WorldEventType.entries.forEach { type ->
                contractJson.decodeFromString<WorldEventType>(contractJson.encodeToString(type)) shouldBe type
            }
        }

        test("a type from a newer server decodes as UNKNOWN instead of failing the sync page") {
            contractJson.decodeFromString<WorldEventType>("\"MARRIES\"") shouldBe WorldEventType.UNKNOWN
        }

        test("fromName maps exact upper-case names and nothing else") {
            WorldEventType.fromName("BELONGS_TO") shouldBe WorldEventType.BELONGS_TO
            WorldEventType.fromName("joins") shouldBe WorldEventType.UNKNOWN
        }

        test("WorldEventId refuses blanks") {
            shouldThrow<IllegalArgumentException> { WorldEventId(" ") }
            WorldEventId("w1").toString() shouldBe "w1"
        }
    })
