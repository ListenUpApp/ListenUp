package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.StoryWorldHistoryId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EntityKindContractTest :
    FunSpec({
        test("every known kind round-trips by name") {
            EntityKind.entries.forEach { kind ->
                contractJson.decodeFromString<EntityKind>(contractJson.encodeToString(kind)) shouldBe kind
            }
        }

        test("a kind from a newer server decodes as UNKNOWN instead of failing the sync page") {
            contractJson.decodeFromString<EntityKind>("\"FACTION\"") shouldBe EntityKind.UNKNOWN
        }

        test("fromName maps unrecognised storage values to UNKNOWN") {
            EntityKind.fromName("GROUP") shouldBe EntityKind.GROUP
            EntityKind.fromName("faction") shouldBe EntityKind.UNKNOWN
        }

        test("the typed ids refuse blanks") {
            shouldThrow<IllegalArgumentException> { EntityId(" ") }
            shouldThrow<IllegalArgumentException> { StoryWorldHistoryId("") }
            EntityId("e1").toString() shouldBe "e1"
        }
    })
