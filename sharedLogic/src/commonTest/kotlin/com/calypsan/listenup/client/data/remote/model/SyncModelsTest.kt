package com.calypsan.listenup.client.data.remote.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json

class SyncModelsTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        test("syncManifestResponse_deserializesFromJson") {
            val jsonString =
                """
                {
                    "library_version": "2025-11-22T14:30:45Z",
                    "checkpoint": "2025-11-22T14:30:45Z",
                    "book_ids": ["book-123", "book-456"],
                    "counts": {
                        "books": 2,
                        "contributors": 5,
                        "series": 1
                    }
                }
                """.trimIndent()

            val response = json.decodeFromString<SyncManifestResponse>(jsonString)

            response.libraryVersion shouldBe "2025-11-22T14:30:45Z"
            response.checkpoint shouldBe "2025-11-22T14:30:45Z"
            response.bookIds.size shouldBe 2
            response.counts.books shouldBe 2
        }

        test("bookResponse_deserializesFromJson") {
            val jsonString =
                """
                {
                    "id": "book-123",
                    "created_at": "2025-11-22T10:00:00Z",
                    "updated_at": "2025-11-22T14:30:45Z",
                    "deleted_at": null,
                    "title": "Test Book",
                    "author": "Test Author",
                    "total_duration": 3600000
                }
                """.trimIndent()

            val book = json.decodeFromString<BookResponse>(jsonString)

            book.id shouldBe "book-123"
            book.title shouldBe "Test Book"
            book.updatedAt shouldNotBe null
        }

        test("syncBooksResponse_deserializesFromJson") {
            val jsonString =
                """
                {
                    "next_cursor": "abc123",
                    "books": [],
                    "has_more": true
                }
                """.trimIndent()

            val response = json.decodeFromString<SyncBooksResponse>(jsonString)

            response.nextCursor shouldBe "abc123"
            response.hasMore shouldBe true
        }

        test("syncBooksResponse_deserializesFromJsonWithMissingCursor") {
            // Server uses omitempty on next_cursor, so it won't be present when empty
            val jsonString =
                """
                {
                    "books": [],
                    "has_more": false
                }
                """.trimIndent()

            val response = json.decodeFromString<SyncBooksResponse>(jsonString)

            response.nextCursor shouldBe null
            response.hasMore shouldBe false
        }
    })
