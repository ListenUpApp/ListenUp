package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class EntitySyncPayloadContractTest :
    FunSpec({
        val noBioEntries =
            EntitySyncPayload(
                id = "entity-1",
                kind = EntityKind.CHARACTER,
                name = "The Stranger in Grey",
                homeSeriesId = "series-1",
                imageRef = null,
                bioEntries = emptyList(),
                revision = 1L,
                updatedAt = 1_700_000_000_000L,
                createdAt = 1_700_000_000_000L,
                deletedAt = null,
            )

        val bioEntries =
            listOf(
                BioEntryPayload(
                    id = "bio-1",
                    bookId = null,
                    positionMs = null,
                    mode = BioEntryMode.APPEND,
                    text = "A cloaked figure seen only at the edges of the story.",
                    sortKey = 0,
                ),
                BioEntryPayload(
                    id = "bio-2",
                    bookId = "book-1",
                    positionMs = 3_600_000L,
                    mode = BioEntryMode.REPLACE,
                    text = "Revealed to be the exiled Duke of Ashford.",
                    sortKey = 1,
                ),
                BioEntryPayload(
                    id = "bio-3",
                    bookId = "book-2",
                    positionMs = null,
                    mode = BioEntryMode.APPEND,
                    text = "Returns in the second book, now allied with the crown.",
                    sortKey = 2,
                ),
            )

        val withBioEntries =
            noBioEntries.copy(
                id = "entity-2",
                imageRef = "ref-abc",
                bioEntries = bioEntries,
                revision = 2L,
            )

        test("EntitySyncPayload round-trips with zero bio entries") {
            val json = contractJson.encodeToString(EntitySyncPayload.serializer(), noBioEntries)
            val decoded = contractJson.decodeFromString(EntitySyncPayload.serializer(), json)
            decoded shouldBe noBioEntries
        }

        test("EntitySyncPayload round-trips with N bio entries, preserving every value") {
            val json = contractJson.encodeToString(EntitySyncPayload.serializer(), withBioEntries)
            val decoded = contractJson.decodeFromString(EntitySyncPayload.serializer(), json)
            decoded shouldBe withBioEntries
            decoded.bioEntries shouldBe bioEntries
        }

        test("BioEntryPayload preserves a null anchor (always-visible baseline)") {
            val entry = bioEntries[0]
            val json = contractJson.encodeToString(BioEntryPayload.serializer(), entry)
            val decoded = contractJson.decodeFromString(BioEntryPayload.serializer(), json)
            decoded.bookId shouldBe null
            decoded.positionMs shouldBe null
        }

        test("BioEntryPayload preserves a fully-set anchor (bookId + positionMs)") {
            val entry = bioEntries[1]
            val json = contractJson.encodeToString(BioEntryPayload.serializer(), entry)
            val decoded = contractJson.decodeFromString(BioEntryPayload.serializer(), json)
            decoded.bookId shouldBe "book-1"
            decoded.positionMs shouldBe 3_600_000L
        }

        test("BioEntryPayload preserves a book-only anchor (null positionMs)") {
            val entry = bioEntries[2]
            val json = contractJson.encodeToString(BioEntryPayload.serializer(), entry)
            val decoded = contractJson.decodeFromString(BioEntryPayload.serializer(), json)
            decoded.bookId shouldBe "book-2"
            decoded.positionMs shouldBe null
        }

        test("EntitySyncPayload tolerates an unknown field on decode (forward compat)") {
            val full =
                contractJson
                    .parseToJsonElement(
                        contractJson.encodeToString(EntitySyncPayload.serializer(), withBioEntries),
                    ).jsonObject
            val withExtraField =
                JsonObject(
                    full.toMutableMap().apply {
                        put("aFieldFromTheFuture", JsonPrimitive("some value a newer server added"))
                    },
                )
            val json = contractJson.encodeToString(withExtraField)
            val decoded = contractJson.decodeFromString(EntitySyncPayload.serializer(), json)
            decoded shouldBe withBioEntries
        }

        test("BioEntryPayload tolerates an unknown field on decode (forward compat)") {
            val full =
                contractJson
                    .parseToJsonElement(
                        contractJson.encodeToString(BioEntryPayload.serializer(), bioEntries[1]),
                    ).jsonObject
            val withExtraField =
                JsonObject(
                    full.toMutableMap().apply {
                        put("aFieldFromTheFuture", JsonPrimitive("some value a newer server added"))
                    },
                )
            val json = contractJson.encodeToString(withExtraField)
            val decoded = contractJson.decodeFromString(BioEntryPayload.serializer(), json)
            decoded shouldBe bioEntries[1]
        }

        test("EntityKind wire tokens are pinned") {
            contractJson.encodeToString(EntityKind.serializer(), EntityKind.CHARACTER) shouldBe "\"CHARACTER\""
            contractJson.encodeToString(EntityKind.serializer(), EntityKind.LOCATION) shouldBe "\"LOCATION\""
            contractJson.encodeToString(EntityKind.serializer(), EntityKind.ITEM) shouldBe "\"ITEM\""
        }

        test("BioEntryMode wire tokens are pinned") {
            contractJson.encodeToString(BioEntryMode.serializer(), BioEntryMode.APPEND) shouldBe "\"APPEND\""
            contractJson.encodeToString(BioEntryMode.serializer(), BioEntryMode.REPLACE) shouldBe "\"REPLACE\""
        }
    })
