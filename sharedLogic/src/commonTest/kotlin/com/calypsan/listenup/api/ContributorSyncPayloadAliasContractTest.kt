package com.calypsan.listenup.api

import com.calypsan.listenup.api.sync.ContributorSyncPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Round-trips the Books-C2 `aliases` field on [ContributorSyncPayload] through [contractJson].
 * Covers the populated case and the backwards-compat case where a pre-C2 payload omits the
 * field entirely — the default must decode to [emptyList].
 */
class ContributorSyncPayloadAliasContractTest :
    FunSpec({

        test("should round-trip aliases when populated") {
            val original =
                ContributorSyncPayload(
                    id = "c1",
                    name = "Stephen King",
                    sortName = "King, Stephen",
                    revision = 1L,
                    updatedAt = 1_700_000_000L,
                    createdAt = 1_700_000_000L,
                    deletedAt = null,
                    aliases = listOf("Richard Bachman", "John Swithen"),
                )
            roundTrip<ContributorSyncPayload>(original) shouldBe original
            roundTrip<ContributorSyncPayload>(original).aliases shouldContainExactly
                listOf("Richard Bachman", "John Swithen")
        }

        test("should round-trip aliases when list is empty") {
            val original =
                ContributorSyncPayload(
                    id = "c2",
                    name = "Brandon Sanderson",
                    sortName = "Sanderson, Brandon",
                    revision = 2L,
                    updatedAt = 1_700_000_001L,
                    createdAt = 1_700_000_001L,
                    deletedAt = null,
                    aliases = emptyList(),
                )
            roundTrip<ContributorSyncPayload>(original) shouldBe original
            roundTrip<ContributorSyncPayload>(original).aliases.shouldBeEmpty()
        }

        test("should default aliases to emptyList when JSON omits the field") {
            // Simulates a payload serialized before Books-C2, which lacks the "aliases" key.
            // Every no-default field must be present; only "aliases" (which has = emptyList())
            // is omitted to prove backwards-compatibility.
            val jsonWithoutAliases =
                """
                {
                    "type": "ContributorSyncPayload",
                    "id": "c3",
                    "name": "Terry Pratchett",
                    "sortName": null,
                    "revision": 3,
                    "updatedAt": 1700000002,
                    "createdAt": 1700000002,
                    "deletedAt": null
                }
                """.trimIndent()
            val decoded = contractJson.decodeFromString<ContributorSyncPayload>(jsonWithoutAliases)
            decoded.aliases.shouldBeEmpty()
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = contractJson.decodeFromString<T>(contractJson.encodeToString(value))
