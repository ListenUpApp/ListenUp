package com.calypsan.listenup.client.presentation.library

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for SortState, SortDirection, and SortCategory.
 *
 * Covers:
 * - Direction toggling
 * - Category default directions
 * - Persistence key encoding/decoding
 * - State transitions
 */
class SortStateTest :
    FunSpec({
        // ========== SortDirection Tests ==========

        test("SortDirection toggle switches ascending to descending") {
            SortDirection.ASCENDING.toggle() shouldBe SortDirection.DESCENDING
        }

        test("SortDirection toggle switches descending to ascending") {
            SortDirection.DESCENDING.toggle() shouldBe SortDirection.ASCENDING
        }

        test("SortDirection key returns lowercase name") {
            SortDirection.ASCENDING.key shouldBe "ascending"
            SortDirection.DESCENDING.key shouldBe "descending"
        }

        test("SortDirection fromKey parses valid keys") {
            SortDirection.fromKey("ascending") shouldBe SortDirection.ASCENDING
            SortDirection.fromKey("descending") shouldBe SortDirection.DESCENDING
        }

        test("SortDirection fromKey returns null for invalid key") {
            SortDirection.fromKey("invalid") shouldBe null
            SortDirection.fromKey("") shouldBe null
            SortDirection.fromKey("ASCENDING") shouldBe null
        }

        // ========== SortCategory Tests ==========

        test("SortCategory key returns lowercase name") {
            SortCategory.TITLE.key shouldBe "title"
            SortCategory.AUTHOR.key shouldBe "author"
            SortCategory.DURATION.key shouldBe "duration"
        }

        test("SortCategory fromKey parses valid keys") {
            SortCategory.fromKey("title") shouldBe SortCategory.TITLE
            SortCategory.fromKey("author") shouldBe SortCategory.AUTHOR
            SortCategory.fromKey("duration") shouldBe SortCategory.DURATION
            SortCategory.fromKey("year") shouldBe SortCategory.YEAR
            SortCategory.fromKey("added") shouldBe SortCategory.ADDED
            SortCategory.fromKey("series") shouldBe SortCategory.SERIES
            SortCategory.fromKey("name") shouldBe SortCategory.NAME
            SortCategory.fromKey("book_count") shouldBe SortCategory.BOOK_COUNT
        }

        test("SortCategory fromKey returns null for invalid key") {
            SortCategory.fromKey("invalid") shouldBe null
            SortCategory.fromKey("") shouldBe null
        }

        test("SortCategory text sorts default to ascending") {
            SortCategory.TITLE.defaultDirection shouldBe SortDirection.ASCENDING
            SortCategory.AUTHOR.defaultDirection shouldBe SortDirection.ASCENDING
            SortCategory.NAME.defaultDirection shouldBe SortDirection.ASCENDING
        }

        test("SortCategory numeric sorts default to descending") {
            SortCategory.DURATION.defaultDirection shouldBe SortDirection.DESCENDING
            SortCategory.YEAR.defaultDirection shouldBe SortDirection.DESCENDING
            SortCategory.BOOK_COUNT.defaultDirection shouldBe SortDirection.DESCENDING
            SortCategory.ADDED.defaultDirection shouldBe SortDirection.DESCENDING
        }

        test("SortCategory directionLabel returns correct labels") {
            SortCategory.TITLE.directionLabel(SortDirection.ASCENDING) shouldBe "A → Z"
            SortCategory.TITLE.directionLabel(SortDirection.DESCENDING) shouldBe "Z → A"

            SortCategory.DURATION.directionLabel(SortDirection.ASCENDING) shouldBe "Shortest"
            SortCategory.DURATION.directionLabel(SortDirection.DESCENDING) shouldBe "Longest"

            SortCategory.YEAR.directionLabel(SortDirection.ASCENDING) shouldBe "Oldest"
            SortCategory.YEAR.directionLabel(SortDirection.DESCENDING) shouldBe "Newest"

            SortCategory.ADDED.directionLabel(SortDirection.ASCENDING) shouldBe "First"
            SortCategory.ADDED.directionLabel(SortDirection.DESCENDING) shouldBe "Recent"

            SortCategory.BOOK_COUNT.directionLabel(SortDirection.ASCENDING) shouldBe "Fewest"
            SortCategory.BOOK_COUNT.directionLabel(SortDirection.DESCENDING) shouldBe "Most"
        }

        test("SortCategory booksCategories contains expected categories") {
            val categories = SortCategory.booksCategories
            (SortCategory.TITLE in categories) shouldBe true
            (SortCategory.AUTHOR in categories) shouldBe true
            (SortCategory.DURATION in categories) shouldBe true
            (SortCategory.YEAR in categories) shouldBe true
            (SortCategory.ADDED in categories) shouldBe true
            (SortCategory.SERIES in categories) shouldBe true
        }

        test("SortCategory seriesCategories contains expected categories") {
            val categories = SortCategory.seriesCategories
            (SortCategory.NAME in categories) shouldBe true
            (SortCategory.BOOK_COUNT in categories) shouldBe true
            (SortCategory.ADDED in categories) shouldBe true
        }

        test("SortCategory contributorCategories contains expected categories") {
            val categories = SortCategory.contributorCategories
            (SortCategory.NAME in categories) shouldBe true
            (SortCategory.BOOK_COUNT in categories) shouldBe true
        }

        // ========== SortState Tests ==========

        test("SortState persistenceKey format is correct") {
            val state = SortState(SortCategory.TITLE, SortDirection.ASCENDING)
            state.persistenceKey shouldBe "title:ascending"
        }

        test("SortState persistenceKey with descending direction") {
            val state = SortState(SortCategory.DURATION, SortDirection.DESCENDING)
            state.persistenceKey shouldBe "duration:descending"
        }

        test("SortState fromPersistenceKey parses valid key") {
            val state = SortState.fromPersistenceKey("title:ascending")
            state?.category shouldBe SortCategory.TITLE
            state?.direction shouldBe SortDirection.ASCENDING
        }

        test("SortState fromPersistenceKey parses all category-direction combinations") {
            val state = SortState.fromPersistenceKey("duration:descending")
            state?.category shouldBe SortCategory.DURATION
            state?.direction shouldBe SortDirection.DESCENDING
        }

        test("SortState fromPersistenceKey returns null for invalid format") {
            SortState.fromPersistenceKey("invalid") shouldBe null
            SortState.fromPersistenceKey("") shouldBe null
            SortState.fromPersistenceKey("title") shouldBe null
            SortState.fromPersistenceKey("title:") shouldBe null
            SortState.fromPersistenceKey(":ascending") shouldBe null
            SortState.fromPersistenceKey("title:invalid") shouldBe null
            SortState.fromPersistenceKey("invalid:ascending") shouldBe null
        }

        test("SortState toggleDirection creates new state with toggled direction") {
            val original = SortState(SortCategory.TITLE, SortDirection.ASCENDING)
            val toggled = original.toggleDirection()

            toggled.category shouldBe SortCategory.TITLE
            toggled.direction shouldBe SortDirection.DESCENDING
        }

        test("SortState toggleDirection preserves category") {
            val original = SortState(SortCategory.DURATION, SortDirection.DESCENDING)
            val toggled = original.toggleDirection()

            toggled.category shouldBe SortCategory.DURATION
            toggled.direction shouldBe SortDirection.ASCENDING
        }

        test("SortState withCategory changes category and uses default direction") {
            val original = SortState(SortCategory.TITLE, SortDirection.DESCENDING)
            val changed = original.withCategory(SortCategory.DURATION)

            changed.category shouldBe SortCategory.DURATION
            // DURATION defaults to DESCENDING
            changed.direction shouldBe SortDirection.DESCENDING
        }

        test("SortState withCategory uses new category default direction") {
            val original = SortState(SortCategory.DURATION, SortDirection.ASCENDING)
            val changed = original.withCategory(SortCategory.TITLE)

            changed.category shouldBe SortCategory.TITLE
            // TITLE defaults to ASCENDING
            changed.direction shouldBe SortDirection.ASCENDING
        }

        test("SortState directionLabel delegates to category") {
            val state = SortState(SortCategory.TITLE, SortDirection.ASCENDING)
            state.directionLabel shouldBe "A → Z"

            val state2 = SortState(SortCategory.DURATION, SortDirection.DESCENDING)
            state2.directionLabel shouldBe "Longest"
        }

        // ========== Default States Tests ==========

        test("SortState booksDefault is title ascending") {
            SortState.booksDefault.category shouldBe SortCategory.TITLE
            SortState.booksDefault.direction shouldBe SortDirection.ASCENDING
        }

        test("SortState seriesDefault is name ascending") {
            SortState.seriesDefault.category shouldBe SortCategory.NAME
            SortState.seriesDefault.direction shouldBe SortDirection.ASCENDING
        }

        test("SortState contributorDefault is name ascending") {
            SortState.contributorDefault.category shouldBe SortCategory.NAME
            SortState.contributorDefault.direction shouldBe SortDirection.ASCENDING
        }

        // ========== Round-Trip Tests ==========

        test("SortState persistence roundtrip works") {
            val original = SortState(SortCategory.YEAR, SortDirection.DESCENDING)
            val key = original.persistenceKey
            val restored = SortState.fromPersistenceKey(key)

            restored shouldBe original
        }

        test("SortState all defaults can be persisted and restored") {
            listOf(
                SortState.booksDefault,
                SortState.seriesDefault,
                SortState.contributorDefault,
            ).forEach { original ->
                val key = original.persistenceKey
                val restored = SortState.fromPersistenceKey(key)
                restored shouldBe original
            }
        }
    })
