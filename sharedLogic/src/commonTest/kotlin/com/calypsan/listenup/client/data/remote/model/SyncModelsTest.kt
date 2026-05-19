package com.calypsan.listenup.client.data.remote.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SyncModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun syncManifestResponse_deserializesFromJson() {
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

        assertEquals("2025-11-22T14:30:45Z", response.libraryVersion)
        assertEquals("2025-11-22T14:30:45Z", response.checkpoint)
        assertEquals(2, response.bookIds.size)
        assertEquals(2, response.counts.books)
    }

    @Test
    fun bookResponse_deserializesFromJson() {
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

        assertEquals("book-123", book.id)
        assertEquals("Test Book", book.title)
        assertNotNull(book.updatedAt)
    }

    @Test
    fun syncBooksResponse_deserializesFromJson() {
        val jsonString =
            """
            {
                "next_cursor": "abc123",
                "books": [],
                "has_more": true
            }
            """.trimIndent()

        val response = json.decodeFromString<SyncBooksResponse>(jsonString)

        assertEquals("abc123", response.nextCursor)
        assertEquals(true, response.hasMore)
    }

    @Test
    fun syncBooksResponse_deserializesFromJsonWithMissingCursor() {
        // Server uses omitempty on next_cursor, so it won't be present when empty
        val jsonString =
            """
            {
                "books": [],
                "has_more": false
            }
            """.trimIndent()

        val response = json.decodeFromString<SyncBooksResponse>(jsonString)

        assertEquals(null, response.nextCursor)
        assertEquals(false, response.hasMore)
    }
}
