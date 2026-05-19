package com.calypsan.listenup.client.presentation.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for SortState, SortDirection, and SortCategory.
 *
 * Covers:
 * - Direction toggling
 * - Category default directions
 * - Persistence key encoding/decoding
 * - State transitions
 */
class SortStateTest {
    // ========== SortDirection Tests ==========

    @Test
    fun `SortDirection toggle switches ascending to descending`() {
        assertEquals(SortDirection.DESCENDING, SortDirection.ASCENDING.toggle())
    }

    @Test
    fun `SortDirection toggle switches descending to ascending`() {
        assertEquals(SortDirection.ASCENDING, SortDirection.DESCENDING.toggle())
    }

    @Test
    fun `SortDirection key returns lowercase name`() {
        assertEquals("ascending", SortDirection.ASCENDING.key)
        assertEquals("descending", SortDirection.DESCENDING.key)
    }

    @Test
    fun `SortDirection fromKey parses valid keys`() {
        assertEquals(SortDirection.ASCENDING, SortDirection.fromKey("ascending"))
        assertEquals(SortDirection.DESCENDING, SortDirection.fromKey("descending"))
    }

    @Test
    fun `SortDirection fromKey returns null for invalid key`() {
        assertNull(SortDirection.fromKey("invalid"))
        assertNull(SortDirection.fromKey(""))
        assertNull(SortDirection.fromKey("ASCENDING"))
    }

    // ========== SortCategory Tests ==========

    @Test
    fun `SortCategory key returns lowercase name`() {
        assertEquals("title", SortCategory.TITLE.key)
        assertEquals("author", SortCategory.AUTHOR.key)
        assertEquals("duration", SortCategory.DURATION.key)
    }

    @Test
    fun `SortCategory fromKey parses valid keys`() {
        assertEquals(SortCategory.TITLE, SortCategory.fromKey("title"))
        assertEquals(SortCategory.AUTHOR, SortCategory.fromKey("author"))
        assertEquals(SortCategory.DURATION, SortCategory.fromKey("duration"))
        assertEquals(SortCategory.YEAR, SortCategory.fromKey("year"))
        assertEquals(SortCategory.ADDED, SortCategory.fromKey("added"))
        assertEquals(SortCategory.SERIES, SortCategory.fromKey("series"))
        assertEquals(SortCategory.NAME, SortCategory.fromKey("name"))
        assertEquals(SortCategory.BOOK_COUNT, SortCategory.fromKey("book_count"))
    }

    @Test
    fun `SortCategory fromKey returns null for invalid key`() {
        assertNull(SortCategory.fromKey("invalid"))
        assertNull(SortCategory.fromKey(""))
    }

    @Test
    fun `SortCategory text sorts default to ascending`() {
        assertEquals(SortDirection.ASCENDING, SortCategory.TITLE.defaultDirection)
        assertEquals(SortDirection.ASCENDING, SortCategory.AUTHOR.defaultDirection)
        assertEquals(SortDirection.ASCENDING, SortCategory.NAME.defaultDirection)
    }

    @Test
    fun `SortCategory numeric sorts default to descending`() {
        assertEquals(SortDirection.DESCENDING, SortCategory.DURATION.defaultDirection)
        assertEquals(SortDirection.DESCENDING, SortCategory.YEAR.defaultDirection)
        assertEquals(SortDirection.DESCENDING, SortCategory.BOOK_COUNT.defaultDirection)
        assertEquals(SortDirection.DESCENDING, SortCategory.ADDED.defaultDirection)
    }

    @Test
    fun `SortCategory directionLabel returns correct labels`() {
        assertEquals("A → Z", SortCategory.TITLE.directionLabel(SortDirection.ASCENDING))
        assertEquals("Z → A", SortCategory.TITLE.directionLabel(SortDirection.DESCENDING))

        assertEquals("Shortest", SortCategory.DURATION.directionLabel(SortDirection.ASCENDING))
        assertEquals("Longest", SortCategory.DURATION.directionLabel(SortDirection.DESCENDING))

        assertEquals("Oldest", SortCategory.YEAR.directionLabel(SortDirection.ASCENDING))
        assertEquals("Newest", SortCategory.YEAR.directionLabel(SortDirection.DESCENDING))

        assertEquals("First", SortCategory.ADDED.directionLabel(SortDirection.ASCENDING))
        assertEquals("Recent", SortCategory.ADDED.directionLabel(SortDirection.DESCENDING))

        assertEquals("Fewest", SortCategory.BOOK_COUNT.directionLabel(SortDirection.ASCENDING))
        assertEquals("Most", SortCategory.BOOK_COUNT.directionLabel(SortDirection.DESCENDING))
    }

    @Test
    fun `SortCategory booksCategories contains expected categories`() {
        val categories = SortCategory.booksCategories
        assertTrue(SortCategory.TITLE in categories)
        assertTrue(SortCategory.AUTHOR in categories)
        assertTrue(SortCategory.DURATION in categories)
        assertTrue(SortCategory.YEAR in categories)
        assertTrue(SortCategory.ADDED in categories)
        assertTrue(SortCategory.SERIES in categories)
    }

    @Test
    fun `SortCategory seriesCategories contains expected categories`() {
        val categories = SortCategory.seriesCategories
        assertTrue(SortCategory.NAME in categories)
        assertTrue(SortCategory.BOOK_COUNT in categories)
        assertTrue(SortCategory.ADDED in categories)
    }

    @Test
    fun `SortCategory contributorCategories contains expected categories`() {
        val categories = SortCategory.contributorCategories
        assertTrue(SortCategory.NAME in categories)
        assertTrue(SortCategory.BOOK_COUNT in categories)
    }

    // ========== SortState Tests ==========

    @Test
    fun `SortState persistenceKey format is correct`() {
        val state = SortState(SortCategory.TITLE, SortDirection.ASCENDING)
        assertEquals("title:ascending", state.persistenceKey)
    }

    @Test
    fun `SortState persistenceKey with descending direction`() {
        val state = SortState(SortCategory.DURATION, SortDirection.DESCENDING)
        assertEquals("duration:descending", state.persistenceKey)
    }

    @Test
    fun `SortState fromPersistenceKey parses valid key`() {
        val state = SortState.fromPersistenceKey("title:ascending")
        assertEquals(SortCategory.TITLE, state?.category)
        assertEquals(SortDirection.ASCENDING, state?.direction)
    }

    @Test
    fun `SortState fromPersistenceKey parses all category-direction combinations`() {
        val state = SortState.fromPersistenceKey("duration:descending")
        assertEquals(SortCategory.DURATION, state?.category)
        assertEquals(SortDirection.DESCENDING, state?.direction)
    }

    @Test
    fun `SortState fromPersistenceKey returns null for invalid format`() {
        assertNull(SortState.fromPersistenceKey("invalid"))
        assertNull(SortState.fromPersistenceKey(""))
        assertNull(SortState.fromPersistenceKey("title"))
        assertNull(SortState.fromPersistenceKey("title:"))
        assertNull(SortState.fromPersistenceKey(":ascending"))
        assertNull(SortState.fromPersistenceKey("title:invalid"))
        assertNull(SortState.fromPersistenceKey("invalid:ascending"))
    }

    @Test
    fun `SortState toggleDirection creates new state with toggled direction`() {
        val original = SortState(SortCategory.TITLE, SortDirection.ASCENDING)
        val toggled = original.toggleDirection()

        assertEquals(SortCategory.TITLE, toggled.category)
        assertEquals(SortDirection.DESCENDING, toggled.direction)
    }

    @Test
    fun `SortState toggleDirection preserves category`() {
        val original = SortState(SortCategory.DURATION, SortDirection.DESCENDING)
        val toggled = original.toggleDirection()

        assertEquals(SortCategory.DURATION, toggled.category)
        assertEquals(SortDirection.ASCENDING, toggled.direction)
    }

    @Test
    fun `SortState withCategory changes category and uses default direction`() {
        val original = SortState(SortCategory.TITLE, SortDirection.DESCENDING)
        val changed = original.withCategory(SortCategory.DURATION)

        assertEquals(SortCategory.DURATION, changed.category)
        // DURATION defaults to DESCENDING
        assertEquals(SortDirection.DESCENDING, changed.direction)
    }

    @Test
    fun `SortState withCategory uses new category default direction`() {
        val original = SortState(SortCategory.DURATION, SortDirection.ASCENDING)
        val changed = original.withCategory(SortCategory.TITLE)

        assertEquals(SortCategory.TITLE, changed.category)
        // TITLE defaults to ASCENDING
        assertEquals(SortDirection.ASCENDING, changed.direction)
    }

    @Test
    fun `SortState directionLabel delegates to category`() {
        val state = SortState(SortCategory.TITLE, SortDirection.ASCENDING)
        assertEquals("A → Z", state.directionLabel)

        val state2 = SortState(SortCategory.DURATION, SortDirection.DESCENDING)
        assertEquals("Longest", state2.directionLabel)
    }

    // ========== Default States Tests ==========

    @Test
    fun `SortState booksDefault is title ascending`() {
        assertEquals(SortCategory.TITLE, SortState.booksDefault.category)
        assertEquals(SortDirection.ASCENDING, SortState.booksDefault.direction)
    }

    @Test
    fun `SortState seriesDefault is name ascending`() {
        assertEquals(SortCategory.NAME, SortState.seriesDefault.category)
        assertEquals(SortDirection.ASCENDING, SortState.seriesDefault.direction)
    }

    @Test
    fun `SortState contributorDefault is name ascending`() {
        assertEquals(SortCategory.NAME, SortState.contributorDefault.category)
        assertEquals(SortDirection.ASCENDING, SortState.contributorDefault.direction)
    }

    // ========== Round-Trip Tests ==========

    @Test
    fun `SortState persistence roundtrip works`() {
        val original = SortState(SortCategory.YEAR, SortDirection.DESCENDING)
        val key = original.persistenceKey
        val restored = SortState.fromPersistenceKey(key)

        assertEquals(original, restored)
    }

    @Test
    fun `SortState all defaults can be persisted and restored`() {
        listOf(
            SortState.booksDefault,
            SortState.seriesDefault,
            SortState.contributorDefault,
        ).forEach { original ->
            val key = original.persistenceKey
            val restored = SortState.fromPersistenceKey(key)
            assertEquals(original, restored)
        }
    }
}
