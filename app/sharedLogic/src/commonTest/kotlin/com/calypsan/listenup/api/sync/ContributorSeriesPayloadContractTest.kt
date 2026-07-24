package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContributorSeriesPayloadContractTest :
    FunSpec({

        test("ContributorSyncPayload survives a JSON round-trip") {
            val payload =
                ContributorSyncPayload(
                    id = "c1",
                    name = "Brandon Sanderson",
                    sortName = "Sanderson, Brandon",
                    revision = 7L,
                    updatedAt = 1_730_000_000_000L,
                    createdAt = 1_720_000_000_000L,
                    deletedAt = null,
                )
            val encoded = contractJson.encodeToString(ContributorSyncPayload.serializer(), payload)
            contractJson.decodeFromString(ContributorSyncPayload.serializer(), encoded) shouldBe payload
        }

        test("ContributorSyncPayload survives a round-trip with null sortName and a tombstone") {
            val payload =
                ContributorSyncPayload(
                    id = "c2",
                    name = "Anonymous",
                    sortName = null,
                    revision = 9L,
                    updatedAt = 1_730_000_000_000L,
                    createdAt = 1_720_000_000_000L,
                    deletedAt = 1_730_000_500_000L,
                )
            val encoded = contractJson.encodeToString(ContributorSyncPayload.serializer(), payload)
            contractJson.decodeFromString(ContributorSyncPayload.serializer(), encoded) shouldBe payload
        }

        test("SeriesSyncPayload survives a JSON round-trip, including a tombstone") {
            val payload =
                SeriesSyncPayload(
                    id = "s1",
                    name = "The Stormlight Archive",
                    sortName = null,
                    revision = 3L,
                    updatedAt = 1_730_000_000_000L,
                    createdAt = 1_720_000_000_000L,
                    deletedAt = 1_730_000_500_000L,
                )
            val encoded = contractJson.encodeToString(SeriesSyncPayload.serializer(), payload)
            contractJson.decodeFromString(SeriesSyncPayload.serializer(), encoded) shouldBe payload
        }
    })
