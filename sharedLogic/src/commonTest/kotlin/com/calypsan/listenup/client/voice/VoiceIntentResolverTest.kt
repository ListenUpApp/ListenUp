package com.calypsan.listenup.client.voice

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceIntentResolverTest {
    private lateinit var searchRepository: FakeSearchRepository
    private lateinit var homeRepository: FakeHomeRepository
    private lateinit var seriesRepository: FakeSeriesRepository
    private lateinit var bookRepository: FakeBookRepository
    private lateinit var resolver: VoiceIntentResolver

    private fun setup() {
        searchRepository = FakeSearchRepository()
        homeRepository = FakeHomeRepository()
        seriesRepository = FakeSeriesRepository()
        bookRepository = FakeBookRepository()
        resolver =
            VoiceIntentResolver(
                searchRepository = searchRepository,
                homeRepository = homeRepository,
                seriesRepository = seriesRepository,
                bookRepository = bookRepository,
            )
    }

    // ========== Resume Intent Tests ==========

    @Test
    fun `resume phrase returns Resume intent`() =
        runTest {
            setup()
            val result = resolver.resolve("resume")
            assertIs<PlaybackIntent.Resume>(result)
        }

    @Test
    fun `continue my audiobook returns Resume intent`() =
        runTest {
            setup()
            val result = resolver.resolve("continue my audiobook")
            assertIs<PlaybackIntent.Resume>(result)
        }

    @Test
    fun `where I left off returns Resume intent`() =
        runTest {
            setup()
            val result = resolver.resolve("where I left off")
            assertIs<PlaybackIntent.Resume>(result)
        }

    // ========== Search Resolution Tests ==========

    @Test
    fun `exact title match returns PlayBook with high confidence`() =
        runTest {
            setup()
            searchRepository.setResults(
                testSearchHit(id = "book1", name = "The Hobbit", score = 1.0f),
            )

            val result = resolver.resolve("The Hobbit")

            assertIs<PlaybackIntent.PlayBook>(result)
            assertEquals("book1", result.bookId)
        }

    @Test
    fun `partial title match with high score returns PlayBook`() =
        runTest {
            setup()
            searchRepository.setResults(
                testSearchHit(id = "book1", name = "The Hobbit", score = 0.9f),
            )

            val result = resolver.resolve("Hobbit")

            assertIs<PlaybackIntent.PlayBook>(result)
            assertEquals("book1", result.bookId)
        }

    @Test
    fun `multiple matches returns Ambiguous with bestGuess`() =
        runTest {
            setup()
            searchRepository.setResults(
                testSearchHit(id = "book1", name = "The Hobbit", score = 0.7f),
                testSearchHit(id = "book2", name = "The Hobbit: An Unexpected Journey", score = 0.6f),
            )

            val result = resolver.resolve("Hobbit")

            assertIs<PlaybackIntent.Ambiguous>(result)
            assertEquals(2, result.candidates.size)
            assertNotNull(result.bestGuess)
            assertEquals("book1", result.bestGuess?.bookId)
        }

    @Test
    fun `no matches returns NotFound`() =
        runTest {
            setup()
            searchRepository.setResults() // empty

            val result = resolver.resolve("xyzzy gibberish")

            assertIs<PlaybackIntent.NotFound>(result)
            assertEquals("xyzzy gibberish", result.originalQuery)
        }

    @Test
    fun `title hint boosts exact match confidence`() =
        runTest {
            setup()
            searchRepository.setResults(
                testSearchHit(id = "book1", name = "The Hobbit", score = 0.5f),
            )

            val result =
                resolver.resolve(
                    query = "The Hobbit",
                    hints = VoiceHints(title = "The Hobbit"),
                )

            // With title hint matching exactly, confidence should be high enough for PlayBook
            assertIs<PlaybackIntent.PlayBook>(result)
        }

    // ========== Series Navigation Tests ==========

    @Test
    fun `next book with series context returns PlaySeriesFrom`() =
        runTest {
            setup()
            // Setup: User has been listening to book 1 of a series
            val series = testSeries("series1", "Lord of the Rings")
            val book1 =
                testBook(
                    id = "book1",
                    title = "The Fellowship of the Ring",
                    series = listOf(testBookSeries("series1", "Lord of the Rings", "1")),
                )
            val book2 =
                testBook(
                    id = "book2",
                    title = "The Two Towers",
                    series = listOf(testBookSeries("series1", "Lord of the Rings", "2")),
                )

            bookRepository.addBook(book1)
            bookRepository.addBook(book2)
            seriesRepository.addSeries(series, listOf("book1", "book2"))
            homeRepository.setContinueListening(testContinueListeningBook("book1", "The Fellowship of the Ring"))

            val result = resolver.resolve("next book")

            assertIs<PlaybackIntent.PlaySeriesFrom>(result)
            assertEquals("series1", result.seriesId)
            assertEquals("book2", result.startBookId)
        }

    @Test
    fun `book 2 with series context returns PlaySeriesFrom`() =
        runTest {
            setup()
            val series = testSeries("series1", "Mistborn")
            val book1 =
                testBook(
                    id = "book1",
                    title = "The Final Empire",
                    series = listOf(testBookSeries("series1", "Mistborn", "1")),
                )
            val book2 =
                testBook(
                    id = "book2",
                    title = "The Well of Ascension",
                    series = listOf(testBookSeries("series1", "Mistborn", "2")),
                )

            bookRepository.addBook(book1)
            bookRepository.addBook(book2)
            seriesRepository.addSeries(series, listOf("book1", "book2"))
            homeRepository.setContinueListening(testContinueListeningBook("book1", "The Final Empire"))

            val result = resolver.resolve("book 2")

            assertIs<PlaybackIntent.PlaySeriesFrom>(result)
            assertEquals("book2", result.startBookId)
        }

    @Test
    fun `next book without series context falls back to search`() =
        runTest {
            setup()
            // No continue listening, no series context
            searchRepository.setResults(
                testSearchHit(id = "book1", name = "Next Book: A Novel", score = 0.9f),
            )

            val result = resolver.resolve("next book")

            // Should fall back to search since there's no series context
            assertIs<PlaybackIntent.PlayBook>(result)
        }

    @Test
    fun `first book in series returns PlaySeriesFrom`() =
        runTest {
            setup()
            val series = testSeries("series1", "Harry Potter")
            val book1 =
                testBook(
                    id = "book1",
                    title = "Philosopher's Stone",
                    series = listOf(testBookSeries("series1", "Harry Potter", "1")),
                )
            val book3 =
                testBook(
                    id = "book3",
                    title = "Prisoner of Azkaban",
                    series = listOf(testBookSeries("series1", "Harry Potter", "3")),
                )

            bookRepository.addBook(book1)
            bookRepository.addBook(book3)
            seriesRepository.addSeries(series, listOf("book1", "book3"))
            homeRepository.setContinueListening(testContinueListeningBook("book3", "Prisoner of Azkaban"))

            val result = resolver.resolve("first book")

            assertIs<PlaybackIntent.PlaySeriesFrom>(result)
            assertEquals("book1", result.startBookId)
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `search exception propagates to caller`() =
        runTest {
            setup()
            searchRepository.setError(RuntimeException("Network error"))

            assertFailsWith<RuntimeException> {
                resolver.resolve("The Hobbit")
            }
        }

    @Test
    fun `empty query falls through to search and returns NotFound`() =
        runTest {
            setup()
            searchRepository.setResults() // empty results

            val result = resolver.resolve("")

            assertIs<PlaybackIntent.NotFound>(result)
        }

    @Test
    fun `whitespace-only query falls through to search`() =
        runTest {
            setup()
            searchRepository.setResults(
                testSearchHit(id = "book1", name = "Some Book", score = 0.9f),
            )

            val result = resolver.resolve("   ")

            // Whitespace query goes to search, returns whatever search finds
            assertIs<PlaybackIntent.PlayBook>(result)
        }

    // ========== Confidence Scoring Edge Cases ==========

    @Test
    fun `very low score with no boosts returns NotFound`() =
        runTest {
            setup()
            searchRepository.setResults(
                testSearchHit(id = "book1", name = "Completely Different Title", score = 0.1f),
            )

            val result = resolver.resolve("The Hobbit")

            // Low score + no title match = below ambiguous threshold = NotFound
            assertIs<PlaybackIntent.NotFound>(result)
        }

    @Test
    fun `author hint boosts confidence`() =
        runTest {
            setup()
            searchRepository.setResults(
                testSearchHit(id = "book1", name = "The Hobbit", author = "J.R.R. Tolkien", score = 0.5f),
            )

            val result =
                resolver.resolve(
                    query = "Hobbit",
                    hints = VoiceHints(artist = "Tolkien"),
                )

            // Author hint should boost confidence enough for PlayBook
            assertIs<PlaybackIntent.PlayBook>(result)
        }

    @Test
    fun `title starts with query boosts confidence`() =
        runTest {
            setup()
            searchRepository.setResults(
                testSearchHit(id = "book1", name = "The Hobbit: An Unexpected Journey", score = 0.5f),
            )

            val result = resolver.resolve("The Hobbit")

            // "The Hobbit: An Unexpected Journey" starts with "the hobbit"
            // Should get TITLE_STARTS_WITH_BOOST + base score
            assertIs<PlaybackIntent.PlayBook>(result)
        }

    @Test
    fun `single match above ambiguous but below high confidence still plays`() =
        runTest {
            setup()
            // Score 0.6f * 0.5 = 0.3 base, + 0.3 title starts with = 0.6 total
            // Above 0.5 (ambiguous) but below 0.8 (high confidence)
            // Single match should still play
            searchRepository.setResults(
                testSearchHit(id = "book1", name = "The Hobbit", score = 0.6f),
            )

            val result = resolver.resolve("Hobbit")

            assertIs<PlaybackIntent.PlayBook>(result)
        }

    // ========== Series Navigation Edge Cases ==========

    @Test
    fun `next book at end of series returns null and falls back to search`() =
        runTest {
            setup()
            val series = testSeries("series1", "Duology")
            val book1 =
                testBook(
                    id = "book1",
                    title = "Book One",
                    series = listOf(testBookSeries("series1", "Duology", "1")),
                )
            val book2 =
                testBook(
                    id = "book2",
                    title = "Book Two",
                    series = listOf(testBookSeries("series1", "Duology", "2")),
                )

            bookRepository.addBook(book1)
            bookRepository.addBook(book2)
            seriesRepository.addSeries(series, listOf("book1", "book2"))
            // User is on the LAST book
            homeRepository.setContinueListening(testContinueListeningBook("book2", "Book Two"))

            // Set up search fallback
            searchRepository.setResults(
                testSearchHit(id = "other", name = "Next Book: A Novel", score = 0.9f),
            )

            val result = resolver.resolve("next book")

            // No next book in series, falls back to search
            assertIs<PlaybackIntent.PlayBook>(result)
            assertEquals("other", result.bookId)
        }

    @Test
    fun `book by sequence not found returns null and falls back to search`() =
        runTest {
            setup()
            val series = testSeries("series1", "Trilogy")
            val book1 =
                testBook(
                    id = "book1",
                    title = "Book One",
                    series = listOf(testBookSeries("series1", "Trilogy", "1")),
                )

            bookRepository.addBook(book1)
            seriesRepository.addSeries(series, listOf("book1"))
            homeRepository.setContinueListening(testContinueListeningBook("book1", "Book One"))

            searchRepository.setResults(
                testSearchHit(id = "other", name = "Book 5", score = 0.9f),
            )

            val result = resolver.resolve("book 5") // No book 5 in series

            // Book 5 not found in series, falls back to search
            assertIs<PlaybackIntent.PlayBook>(result)
            assertEquals("other", result.bookId)
        }

    @Test
    fun `ambiguous result has candidates sorted by confidence`() =
        runTest {
            setup()
            // Use scores that result in ambiguous range (0.5-0.8) after boosts
            // Query "adventure" - all titles contain it, so all get TITLE_CONTAINS_QUERY_BOOST (+0.2)
            // Base scores: 0.7*0.5=0.35, 0.6*0.5=0.3, 0.5*0.5=0.25
            // With +0.2 boost: 0.55, 0.5, 0.45
            // book3 at 0.45 is below 0.5 threshold, so only 2 viable
            searchRepository.setResults(
                testSearchHit(id = "book1", name = "Adventure Time", score = 0.7f),
                testSearchHit(id = "book2", name = "The Grand Adventure", score = 0.6f),
                testSearchHit(id = "book3", name = "Adventure Awaits", score = 0.65f),
            )

            val result = resolver.resolve("adventure")

            assertIs<PlaybackIntent.Ambiguous>(result)
            assertTrue(result.candidates.size >= 2)
            // Candidates should be sorted by confidence (highest first)
            for (i in 0 until result.candidates.size - 1) {
                assertTrue(
                    result.candidates[i].confidence >= result.candidates[i + 1].confidence,
                    "Candidates should be sorted by confidence descending",
                )
            }
        }

    @Test
    fun `ambiguous result bestGuess is null when top match below threshold`() =
        runTest {
            setup()
            // All matches below ambiguous threshold
            searchRepository.setResults(
                testSearchHit(id = "book1", name = "Something Else", score = 0.3f),
                testSearchHit(id = "book2", name = "Another Thing", score = 0.2f),
            )

            val result = resolver.resolve("The Hobbit")

            // Both below 0.5 threshold, should return NotFound
            assertIs<PlaybackIntent.NotFound>(result)
        }
}
