package com.calypsan.listenup.client.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SeriesNavigationDetectorTest {
    @Test
    fun `next book patterns return Next`() {
        assertIs<SeriesNavigation.Next>(SeriesNavigationDetector.detect("next book"))
        assertIs<SeriesNavigation.Next>(SeriesNavigationDetector.detect("the next one"))
        assertIs<SeriesNavigation.Next>(SeriesNavigationDetector.detect("next in series"))
        assertIs<SeriesNavigation.Next>(SeriesNavigationDetector.detect("play the next one"))
    }

    @Test
    fun `first book patterns return First`() {
        assertIs<SeriesNavigation.First>(SeriesNavigationDetector.detect("first book"))
        assertIs<SeriesNavigation.First>(SeriesNavigationDetector.detect("start of the series"))
        assertIs<SeriesNavigation.First>(SeriesNavigationDetector.detect("beginning"))
    }

    @Test
    fun `numeric sequence returns BySequence`() {
        val result = SeriesNavigationDetector.detect("book 2")
        assertIs<SeriesNavigation.BySequence>(result)
        assertEquals("2", result.sequence)
    }

    @Test
    fun `decimal sequence returns BySequence`() {
        val result = SeriesNavigationDetector.detect("book 1.5")
        assertIs<SeriesNavigation.BySequence>(result)
        assertEquals("1.5", result.sequence)
    }

    @Test
    fun `word numbers return BySequence`() {
        val testCases =
            listOf(
                "second book" to "2",
                "third book" to "3",
                "fourth book" to "4",
                "fifth book" to "5",
            )
        testCases.forEach { (input, expected) ->
            val result = SeriesNavigationDetector.detect(input)
            assertIs<SeriesNavigation.BySequence>(result, "Expected BySequence for '$input'")
            assertEquals(expected, result.sequence, "Expected sequence '$expected' for '$input'")
        }
    }

    @Test
    fun `case insensitive matching`() {
        assertIs<SeriesNavigation.Next>(SeriesNavigationDetector.detect("NEXT BOOK"))
        assertIs<SeriesNavigation.First>(SeriesNavigationDetector.detect("FIRST BOOK"))

        val result = SeriesNavigationDetector.detect("SECOND BOOK")
        assertIs<SeriesNavigation.BySequence>(result)
        assertEquals("2", result.sequence)
    }

    @Test
    fun `unrelated query returns NotSeriesNavigation`() {
        assertIs<SeriesNavigation.NotSeriesNavigation>(SeriesNavigationDetector.detect("The Hobbit"))
        assertIs<SeriesNavigation.NotSeriesNavigation>(SeriesNavigationDetector.detect("play something"))
        assertIs<SeriesNavigation.NotSeriesNavigation>(SeriesNavigationDetector.detect(""))
    }
}
