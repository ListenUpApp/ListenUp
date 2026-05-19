package com.calypsan.listenup.client.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for NanoId generator.
 *
 * Tests cover:
 * - Default ID length (21 characters)
 * - Custom ID length
 * - URL-safe character set
 * - Prefixed ID format
 * - Uniqueness (probabilistic)
 */
class NanoIdTest {
    // URL-safe alphabet used by NanoId
    private val validChars = "_-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    // ========== Default Generation Tests ==========

    @Test
    fun `generate returns 21 character ID by default`() {
        val id = NanoId.generate()
        assertEquals(21, id.length)
    }

    @Test
    fun `generate returns URL-safe characters only`() {
        val id = NanoId.generate()
        assertTrue(id.all { it in validChars })
    }

    @Test
    fun `generate returns different IDs on each call`() {
        val ids = (1..100).map { NanoId.generate() }.toSet()
        assertEquals(100, ids.size, "All 100 generated IDs should be unique")
    }

    // ========== Custom Size Tests ==========

    @Test
    fun `generate with custom size returns correct length`() {
        val id = NanoId.generate(size = 10)
        assertEquals(10, id.length)
    }

    @Test
    fun `generate with size 1 returns single character`() {
        val id = NanoId.generate(size = 1)
        assertEquals(1, id.length)
        assertTrue(id[0] in validChars)
    }

    @Test
    fun `generate with large size returns correct length`() {
        val id = NanoId.generate(size = 100)
        assertEquals(100, id.length)
        assertTrue(id.all { it in validChars })
    }

    // ========== Prefixed Generation Tests ==========

    @Test
    fun `generate with prefix returns prefixed ID`() {
        val id = NanoId.generate(prefix = "evt")
        assertTrue(id.startsWith("evt-"))
        assertEquals(25, id.length) // "evt-" (4 chars) + 21 chars
    }

    @Test
    fun `generate with prefix and custom size returns correct format`() {
        val id = NanoId.generate(prefix = "usr", size = 10)
        assertTrue(id.startsWith("usr-"))
        assertEquals(14, id.length) // "usr-" (4 chars) + 10 chars
    }

    @Test
    fun `generate with different prefixes produces different formats`() {
        val evtId = NanoId.generate(prefix = "evt")
        val usrId = NanoId.generate(prefix = "usr")
        val bookId = NanoId.generate(prefix = "book")

        assertTrue(evtId.startsWith("evt-"))
        assertTrue(usrId.startsWith("usr-"))
        assertTrue(bookId.startsWith("book-"))
    }

    @Test
    fun `prefixed ID contains only valid characters after prefix`() {
        val id = NanoId.generate(prefix = "test")
        val suffix = id.removePrefix("test-")
        assertTrue(suffix.all { it in validChars })
    }

    // ========== Character Distribution Tests ==========

    @Test
    fun `generate uses full alphabet range`() {
        // Generate many IDs and check that we see most characters
        val allChars =
            (1..1000)
                .map { NanoId.generate() }
                .flatMap { it.toList() }
                .toSet()

        // With 1000 IDs of 21 chars each (21000 chars total), we should see
        // most of the 64 character alphabet. Allow for some statistical variance.
        assertTrue(allChars.size >= 50, "Should use most of the 64-char alphabet")
    }

    // ========== Uniqueness Tests ==========

    @Test
    fun `generate produces unique IDs in batch`() {
        val ids = (1..1000).map { NanoId.generate() }
        val uniqueIds = ids.toSet()
        assertEquals(ids.size, uniqueIds.size, "All generated IDs should be unique")
    }

    @Test
    fun `prefixed generate produces unique IDs`() {
        val ids = (1..100).map { NanoId.generate(prefix = "test") }
        val uniqueIds = ids.toSet()
        assertEquals(ids.size, uniqueIds.size, "All prefixed IDs should be unique")
    }
}
