package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DomainDiscoveryContractTest :
    FunSpec({

        test("DomainDigest round-trips") {
            val original = DomainDigest(cursor = 42, count = 7, hash = "sha256:abc123")
            val json = contractJson.encodeToString(DomainDigest.serializer(), original)
            val decoded = contractJson.decodeFromString(DomainDigest.serializer(), json)
            decoded shouldBe original
        }

        test("DomainList round-trips") {
            val original = DomainList(domains = listOf("books", "tags", "shelves"))
            val json = contractJson.encodeToString(DomainList.serializer(), original)
            val decoded = contractJson.decodeFromString(DomainList.serializer(), json)
            decoded shouldBe original
        }

        test("Empty DomainList round-trips") {
            val original = DomainList(domains = emptyList())
            val json = contractJson.encodeToString(DomainList.serializer(), original)
            val decoded = contractJson.decodeFromString(DomainList.serializer(), json)
            decoded shouldBe original
        }
    })
