package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.ChapterId
import com.calypsan.listenup.client.core.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for type-safe value classes: BookId, ChapterId, Timestamp.
 *
 * These tests verify:
 * - Validation constraints
 * - toString behavior
 * - Timestamp arithmetic and comparison
 */
class ValueClassesTest {
    // ========== BookId Tests ==========

    @Test
    fun `BookId stores value correctly`() {
        val bookId = BookId("book-123")
        assertEquals("book-123", bookId.value)
    }

    @Test
    fun `BookId toString returns value`() {
        val bookId = BookId("book-abc")
        assertEquals("book-abc", bookId.toString())
    }

    @Test
    fun `BookId rejects blank value`() {
        assertFailsWith<IllegalArgumentException> {
            BookId("")
        }
    }

    @Test
    fun `BookId rejects whitespace-only value`() {
        assertFailsWith<IllegalArgumentException> {
            BookId("   ")
        }
    }

    @Test
    fun `BookId fromString creates valid id`() {
        val bookId = BookId.fromString("book-456")
        assertEquals("book-456", bookId.value)
    }

    @Test
    fun `BookId equality works correctly`() {
        val id1 = BookId("book-1")
        val id2 = BookId("book-1")
        val id3 = BookId("book-2")

        assertEquals(id1, id2)
        assertTrue(id1 != id3)
    }

    // ========== ChapterId Tests ==========

    @Test
    fun `ChapterId stores value correctly`() {
        val chapterId = ChapterId("chapter-1")
        assertEquals("chapter-1", chapterId.value)
    }

    @Test
    fun `ChapterId toString returns value`() {
        val chapterId = ChapterId("ch-abc")
        assertEquals("ch-abc", chapterId.toString())
    }

    @Test
    fun `ChapterId rejects blank value`() {
        assertFailsWith<IllegalArgumentException> {
            ChapterId("")
        }
    }

    @Test
    fun `ChapterId rejects whitespace-only value`() {
        assertFailsWith<IllegalArgumentException> {
            ChapterId("   ")
        }
    }

    // ========== Timestamp Tests ==========

    @Test
    fun `Timestamp stores epoch millis correctly`() {
        val ts = Timestamp(1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, ts.epochMillis)
    }

    @Test
    fun `Timestamp toString returns epoch millis string`() {
        val ts = Timestamp(1_234_567_890L)
        assertEquals("1234567890", ts.toString())
    }

    @Test
    fun `Timestamp now returns current time`() {
        val before = Clock.System.now().toEpochMilliseconds()
        val ts = Timestamp.now()
        val after = Clock.System.now().toEpochMilliseconds()

        assertTrue(ts.epochMillis >= before)
        assertTrue(ts.epochMillis <= after)
    }

    @Test
    fun `Timestamp compareTo works correctly`() {
        val earlier = Timestamp(1_000L)
        val later = Timestamp(2_000L)

        assertTrue(earlier < later)
        assertTrue(later > earlier)
        assertEquals(0, Timestamp(1_000L).compareTo(Timestamp(1_000L)))
    }

    @Test
    fun `Timestamp minus calculates duration between timestamps`() {
        val earlier = Timestamp(1_000_000L)
        val later = Timestamp(1_000_000L + 3_600_000L) // 1 hour later (3,600,000ms = 1 hour)

        val duration = later - earlier
        assertEquals(1.hours, duration)
    }

    @Test
    fun `Timestamp plus adds duration`() {
        val ts = Timestamp(1_000_000L)
        val result = ts + 1.hours

        // 1_000_000 + 3_600_000 = 4_600_000
        assertEquals(4_600_000L, result.epochMillis)
    }

    @Test
    fun `Timestamp plus handles milliseconds`() {
        val ts = Timestamp(1_000_000L)
        val result = ts + 500.milliseconds

        assertEquals(1_000_500L, result.epochMillis)
    }

    @Test
    fun `Timestamp toIsoString formats correctly`() {
        // Unix timestamp 0 should format to epoch
        val ts = Timestamp(0L)
        assertEquals("1970-01-01T00:00:00Z", ts.toIsoString())
    }
}
