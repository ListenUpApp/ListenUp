package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TagContractTest :
    FunSpec({

        test("Tag round-trips with all fields including slug") {
            val original =
                Tag(
                    id = "tag-1",
                    name = "Sci-Fi",
                    slug = "sci-fi",
                    revision = 42,
                    updatedAt = 1730000000000L,
                    deletedAt = null,
                )
            val json = contractJson.encodeToString(Tag.serializer(), original)
            val decoded = contractJson.decodeFromString(Tag.serializer(), json)
            decoded shouldBe original
        }

        test("Tag round-trips with deletedAt populated (tombstone)") {
            val original =
                Tag(
                    id = "tag-2",
                    name = "Fantasy",
                    slug = "fantasy",
                    revision = 50,
                    updatedAt = 1730000000000L,
                    deletedAt = 1730000005000L,
                )
            val json = contractJson.encodeToString(Tag.serializer(), original)
            val decoded = contractJson.decodeFromString(Tag.serializer(), json)
            decoded shouldBe original
        }
    })
