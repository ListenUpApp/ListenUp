package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class BookTierLabelsPayloadContractTest :
    FunSpec({
        val payload =
            BookSyncPayload(
                id = "book-1",
                libraryId = LibraryId("lib-1"),
                folderId = FolderId("folder-1"),
                title = "The Way of Kings",
                sortTitle = null,
                subtitle = null,
                description = null,
                publishYear = 2010,
                publisher = null,
                language = "en",
                isbn = null,
                asin = null,
                abridged = false,
                explicit = false,
                totalDuration = 360_000L,
                cover = null,
                rootRelPath = "books/book-1",
                inode = null,
                scannedAt = 1_700_000_000_000L,
                contributors = emptyList(),
                series = emptyList(),
                audioFiles = emptyList(),
                chapters = emptyList(),
                bookTierLabel = "Book",
                partTierLabel = "Part",
                revision = 1L,
                updatedAt = 1_700_000_000_000L,
                createdAt = 1_700_000_000_000L,
                deletedAt = null,
            )

        test("BookSyncPayload round-trips bookTierLabel and partTierLabel through contractJson") {
            val json = contractJson.encodeToString(BookSyncPayload.serializer(), payload)
            val decoded = contractJson.decodeFromString(BookSyncPayload.serializer(), json)
            decoded shouldBe payload
            decoded.bookTierLabel shouldBe "Book"
            decoded.partTierLabel shouldBe "Part"
        }

        test("BookSyncPayload without tier-label fields deserializes to null (backward-compat)") {
            // Simulate an older server payload that never carried the fields — structurally
            // remove the keys so this is robust regardless of field content.
            val full =
                contractJson
                    .parseToJsonElement(
                        contractJson.encodeToString(BookSyncPayload.serializer(), payload),
                    ).jsonObject
            val stripped =
                JsonObject(
                    full.toMutableMap().apply {
                        remove("bookTierLabel")
                        remove("partTierLabel")
                    },
                )
            val strippedJson = contractJson.encodeToString(stripped)
            val decoded = contractJson.decodeFromString(BookSyncPayload.serializer(), strippedJson)
            decoded.bookTierLabel shouldBe null
            decoded.partTierLabel shouldBe null
        }
    })
