package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.builtins.serializer

class PageContractTest :
    FunSpec({

        test("Page round-trips with items and cursor") {
            val original = Page(items = listOf("a", "b", "c"), nextCursor = 42L, hasMore = true)
            val json = contractJson.encodeToString(Page.serializer(String.serializer()), original)
            val decoded = contractJson.decodeFromString(Page.serializer(String.serializer()), json)
            decoded shouldBe original
        }

        test("Empty Page round-trips with null cursor and hasMore=false") {
            val original = Page(items = emptyList<String>(), nextCursor = null, hasMore = false)
            val json = contractJson.encodeToString(Page.serializer(String.serializer()), original)
            val decoded = contractJson.decodeFromString(Page.serializer(String.serializer()), json)
            decoded shouldBe original
        }
    })
