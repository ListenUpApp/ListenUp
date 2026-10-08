package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.api.sync.EntityKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EntityKindConverterTest :
    FunSpec({
        test("kinds are stored by name and read back") {
            val converter = EntityKindConverter()
            EntityKind.entries.forEach { converter.toEntityKind(converter.fromEntityKind(it)) shouldBe it }
        }

        test("a stored kind this build doesn't know reads as UNKNOWN instead of throwing") {
            EntityKindConverter().toEntityKind("FACTION") shouldBe EntityKind.UNKNOWN
        }
    })
