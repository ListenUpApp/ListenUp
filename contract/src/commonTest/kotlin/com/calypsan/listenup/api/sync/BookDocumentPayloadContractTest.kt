package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class BookDocumentPayloadContractTest :
    FunSpec({
        val doc1 =
            BookDocumentPayload(
                id = "doc-1",
                index = 0,
                filename = "supplement/companion-guide.pdf",
                format = "pdf",
                size = 1_048_576L,
                hash = "abc123def456",
            )
        val doc2 =
            BookDocumentPayload(
                id = "doc-2",
                index = 1,
                filename = "ebook.epub",
                format = "epub",
                size = 524_288L,
                hash = "deadbeef1234",
            )

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
                documents = listOf(doc1, doc2),
                chapters = emptyList(),
                revision = 1L,
                updatedAt = 1_700_000_000_000L,
                createdAt = 1_700_000_000_000L,
                deletedAt = null,
            )

        test("BookDocumentPayload round-trips through contractJson preserving every field") {
            val json = contractJson.encodeToString(BookDocumentPayload.serializer(), doc1)
            val decoded = contractJson.decodeFromString(BookDocumentPayload.serializer(), json)
            decoded shouldBe doc1
        }

        test("BookSyncPayload with non-empty documents list round-trips") {
            val json = contractJson.encodeToString(BookSyncPayload.serializer(), payload)
            val decoded = contractJson.decodeFromString(BookSyncPayload.serializer(), json)
            decoded shouldBe payload
            decoded.documents shouldBe listOf(doc1, doc2)
        }

        test("BookSyncPayload without documents field deserializes to emptyList (backward-compat)") {
            // Build an older-server payload JSON that never carried the documents field by
            // structurally removing the key — robust against any filename content (a regex
            // on the serialized text is not).
            val full =
                contractJson
                    .parseToJsonElement(
                        contractJson.encodeToString(BookSyncPayload.serializer(), payload),
                    ).jsonObject
            val withoutDocuments = JsonObject(full.toMutableMap().apply { remove("documents") })
            val strippedJson = contractJson.encodeToString(withoutDocuments)
            val decoded = contractJson.decodeFromString(BookSyncPayload.serializer(), strippedJson)
            decoded.documents shouldBe emptyList()
        }
    })
