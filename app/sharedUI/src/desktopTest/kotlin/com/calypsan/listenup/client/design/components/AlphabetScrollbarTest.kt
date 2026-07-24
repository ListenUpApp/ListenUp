package com.calypsan.listenup.client.design.components

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe

/**
 * Tests for [AlphabetIndex.build] and [calculateWeight].
 *
 * [AlphabetIndex.build] extracts each item's leading character (uppercased),
 * records the first-seen list index for each character, and sorts the resulting
 * letter set with non-letters (digits, symbols) before A–Z.
 *
 * [calculateWeight] returns a fisheye weight based on distance from the
 * selected letter index, using constants from [AdaptiveScrollbarConfig].
 */
class AlphabetScrollbarTest :
    FunSpec({
        // =====================================================================
        // AlphabetIndex.build — index building
        // =====================================================================

        test("empty list produces empty AlphabetIndex") {
            val index = AlphabetIndex.build(emptyList<String>()) { it }
            index.letters.shouldBeEmpty()
            index.letterToIndex.shouldBeEmpty()
        }

        test("single item produces one-letter index") {
            val index = AlphabetIndex.build(listOf("Apple")) { it }
            index.letters shouldBe listOf('A')
            index.letterToIndex shouldBe mapOf('A' to 0)
        }

        test("items with different leading letters each get their own entry") {
            val items = listOf("Apple", "Banana", "Cherry")
            val index = AlphabetIndex.build(items) { it }
            index.letters shouldBe listOf('A', 'B', 'C')
            index.letterToIndex shouldBe mapOf('A' to 0, 'B' to 1, 'C' to 2)
        }

        test("duplicate leading letters map to the FIRST occurrence") {
            // 'A' appears at index 0 (Apple) and index 1 (Avocado); only 0 is recorded
            val items = listOf("Apple", "Avocado", "Banana")
            val index = AlphabetIndex.build(items) { it }
            index.letters shouldBe listOf('A', 'B')
            index.letterToIndex['A'] shouldBe 0
        }

        test("leading letter is uppercased") {
            val items = listOf("apple", "banana")
            val index = AlphabetIndex.build(items) { it }
            index.letterToIndex shouldContainKey 'A'
            index.letterToIndex shouldContainKey 'B'
        }

        test("mixed-case items are case-insensitive — same bucket as uppercase") {
            val items = listOf("apple", "Apple", "APPLE")
            val index = AlphabetIndex.build(items) { it }
            // All three start with 'A' (uppercased); only index 0 is recorded
            index.letters shouldBe listOf('A')
            index.letterToIndex['A'] shouldBe 0
        }

        // =====================================================================
        // AlphabetIndex.build — sort order: non-letters first, then A-Z
        // =====================================================================

        test("digits sort before letters") {
            val items = listOf("1 Thing", "Apple")
            val index = AlphabetIndex.build(items) { it }
            // '1' is not a letter → sorts before 'A'
            index.letters[0] shouldBe '1'
            index.letters[1] shouldBe 'A'
        }

        test("symbol sorts before letters") {
            val items = listOf("#tag", "Zebra")
            val index = AlphabetIndex.build(items) { it }
            index.letters[0] shouldBe '#'
            index.letters[1] shouldBe 'Z'
        }

        test("letters are sorted alphabetically A before Z") {
            val items = listOf("Zebra", "Apple", "Mango")
            val index = AlphabetIndex.build(items) { it }
            index.letters shouldBe listOf('A', 'M', 'Z')
        }

        test("digits sort before letters and digit order is preserved") {
            val items = listOf("9Lives", "1Thing", "Apple")
            val index = AlphabetIndex.build(items) { it }
            // Non-letters come first, then sorted; '1' < '9' < 'A'
            index.letters[0] shouldBe '1'
            index.letters[1] shouldBe '9'
            index.letters[2] shouldBe 'A'
        }

        test("mixed letters digits and symbols: non-letters first then letters alphabetically") {
            val items = listOf("Zoo", "#hash", "1one", "Apple")
            val index = AlphabetIndex.build(items) { it }
            val nonLetters = index.letters.filter { !it.isLetter() }
            val letters = index.letters.filter { it.isLetter() }
            // Non-letters come before every letter
            nonLetters.forEach { nl ->
                letters.forEach { l ->
                    val nlPos = index.letters.indexOf(nl)
                    val lPos = index.letters.indexOf(l)
                    (nlPos < lPos) shouldBe true
                }
            }
            // Letters are sorted
            letters shouldBe letters.sorted()
        }

        // =====================================================================
        // AlphabetIndex.build — empty / blank names
        // =====================================================================

        test("items with empty names are skipped") {
            val items = listOf("", "Apple", "")
            val index = AlphabetIndex.build(items) { it }
            index.letters shouldBe listOf('A')
            index.letterToIndex['A'] shouldBe 1
        }

        test("list of only empty-name items produces empty index") {
            val items = listOf("", "", "")
            val index = AlphabetIndex.build(items) { it }
            index.letters.shouldBeEmpty()
        }

        // =====================================================================
        // AlphabetIndex.build — custom key selector
        // =====================================================================

        test("keySelector is applied before extracting leading character") {
            data class Book(
                val title: String,
                val author: String,
            )

            val books =
                listOf(
                    Book("The Way of Kings", "Brandon Sanderson"),
                    Book("Mistborn", "Brandon Sanderson"),
                )
            // Build by title
            val index = AlphabetIndex.build(books) { it.title }
            index.letterToIndex shouldContainKey 'T'
            index.letterToIndex shouldContainKey 'M'
        }

        // =====================================================================
        // AlphabetIndex.letterToIndex maps to list positions
        // =====================================================================

        test("letterToIndex values match the actual list positions") {
            val items = listOf("Cherry", "Apple", "Banana")
            val index = AlphabetIndex.build(items) { it }
            index.letterToIndex['C'] shouldBe 0
            index.letterToIndex['A'] shouldBe 1
            index.letterToIndex['B'] shouldBe 2
        }

        // =====================================================================
        // calculateWeight — fisheye distance weighting
        // =====================================================================

        test("no selection returns AT_REST weight") {
            calculateWeight(index = 3, selectedIndex = null) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_AT_REST
        }

        test("selected index (distance 0) returns WEIGHT_SELECTED") {
            calculateWeight(index = 5, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_SELECTED
        }

        test("distance 1 returns WEIGHT_DISTANCE_1") {
            calculateWeight(index = 4, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANCE_1
            calculateWeight(index = 6, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANCE_1
        }

        test("distance 2 returns WEIGHT_DISTANCE_2") {
            calculateWeight(index = 3, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANCE_2
            calculateWeight(index = 7, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANCE_2
        }

        test("distance 3 or more returns WEIGHT_DISTANT") {
            calculateWeight(index = 2, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANT
            calculateWeight(index = 0, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANT
            calculateWeight(index = 10, selectedIndex = 5) shouldBe
                AdaptiveScrollbarConfig.WEIGHT_DISTANT
        }

        test("weight is symmetric around selectedIndex") {
            val selected = 4
            for (distance in 0..3) {
                val leftWeight = calculateWeight(index = selected - distance, selectedIndex = selected)
                val rightWeight = calculateWeight(index = selected + distance, selectedIndex = selected)
                leftWeight shouldBe rightWeight
            }
        }

        test("weights obey SELECTED > DISTANCE_1 > DISTANCE_2 > DISTANT") {
            val sel = 5
            val wSel = calculateWeight(sel, sel)
            val wD1 = calculateWeight(sel + 1, sel)
            val wD2 = calculateWeight(sel + 2, sel)
            val wDist = calculateWeight(sel + 3, sel)
            (wSel > wD1) shouldBe true
            (wD1 > wD2) shouldBe true
            (wD2 > wDist) shouldBe true
        }

        test("AT_REST weight is returned when selectedIndex is null regardless of index value") {
            for (i in 0..10) {
                calculateWeight(i, null) shouldBe AdaptiveScrollbarConfig.WEIGHT_AT_REST
            }
        }
    })
