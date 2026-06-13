package com.calypsan.listenup.client.voice

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class VoiceIntentResolverTest :
    FunSpec({
        lateinit var searchRepository: FakeSearchRepository
        lateinit var homeRepository: FakeHomeRepository
        lateinit var seriesRepository: FakeSeriesRepository
        lateinit var bookRepository: FakeBookRepository
        lateinit var resolver: VoiceIntentResolver

        fun setup() {
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

        test("resume phrase returns Resume intent") {
            runTest {
                setup()
                val result = resolver.resolve("resume")
                result.shouldBeInstanceOf<PlaybackIntent.Resume>()
            }
        }

        test("continue my audiobook returns Resume intent") {
            runTest {
                setup()
                val result = resolver.resolve("continue my audiobook")
                result.shouldBeInstanceOf<PlaybackIntent.Resume>()
            }
        }

        test("where I left off returns Resume intent") {
            runTest {
                setup()
                val result = resolver.resolve("where I left off")
                result.shouldBeInstanceOf<PlaybackIntent.Resume>()
            }
        }

        // ========== Search Resolution Tests ==========

        test("exact title match returns PlayBook with high confidence") {
            runTest {
                setup()
                searchRepository.setResults(
                    testSearchHit(id = "book1", name = "The Hobbit", score = 1.0f),
                )

                val result = resolver.resolve("The Hobbit")

                val playBook = result.shouldBeInstanceOf<PlaybackIntent.PlayBook>()
                playBook.bookId shouldBe "book1"
            }
        }

        test("partial title match with high score returns PlayBook") {
            runTest {
                setup()
                searchRepository.setResults(
                    testSearchHit(id = "book1", name = "The Hobbit", score = 0.9f),
                )

                val result = resolver.resolve("Hobbit")

                val playBook = result.shouldBeInstanceOf<PlaybackIntent.PlayBook>()
                playBook.bookId shouldBe "book1"
            }
        }

        test("multiple matches returns Ambiguous with bestGuess") {
            runTest {
                setup()
                searchRepository.setResults(
                    testSearchHit(id = "book1", name = "The Hobbit", score = 0.7f),
                    testSearchHit(id = "book2", name = "The Hobbit: An Unexpected Journey", score = 0.6f),
                )

                val result = resolver.resolve("Hobbit")

                val ambiguous = result.shouldBeInstanceOf<PlaybackIntent.Ambiguous>()
                ambiguous.candidates.size shouldBe 2
                ambiguous.bestGuess shouldNotBe null
                ambiguous.bestGuess?.bookId shouldBe "book1"
            }
        }

        test("no matches returns NotFound") {
            runTest {
                setup()
                searchRepository.setResults() // empty

                val result = resolver.resolve("xyzzy gibberish")

                val notFound = result.shouldBeInstanceOf<PlaybackIntent.NotFound>()
                notFound.originalQuery shouldBe "xyzzy gibberish"
            }
        }

        test("title hint boosts exact match confidence") {
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
                result.shouldBeInstanceOf<PlaybackIntent.PlayBook>()
            }
        }

        // ========== Series Navigation Tests ==========

        test("next book with series context returns PlaySeriesFrom") {
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

                val playSeries = result.shouldBeInstanceOf<PlaybackIntent.PlaySeriesFrom>()
                playSeries.seriesId shouldBe "series1"
                playSeries.startBookId shouldBe "book2"
            }
        }

        test("book 2 with series context returns PlaySeriesFrom") {
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

                val playSeries = result.shouldBeInstanceOf<PlaybackIntent.PlaySeriesFrom>()
                playSeries.startBookId shouldBe "book2"
            }
        }

        test("next book without series context falls back to search") {
            runTest {
                setup()
                // No continue listening, no series context
                searchRepository.setResults(
                    testSearchHit(id = "book1", name = "Next Book: A Novel", score = 0.9f),
                )

                val result = resolver.resolve("next book")

                // Should fall back to search since there's no series context
                result.shouldBeInstanceOf<PlaybackIntent.PlayBook>()
            }
        }

        test("first book in series returns PlaySeriesFrom") {
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

                val playSeries = result.shouldBeInstanceOf<PlaybackIntent.PlaySeriesFrom>()
                playSeries.startBookId shouldBe "book1"
            }
        }

        // ========== Error Handling Tests ==========

        test("search exception propagates to caller") {
            runTest {
                setup()
                searchRepository.setError(RuntimeException("Network error"))

                shouldThrow<RuntimeException> {
                    resolver.resolve("The Hobbit")
                }
            }
        }

        test("empty query falls through to search and returns NotFound") {
            runTest {
                setup()
                searchRepository.setResults() // empty results

                val result = resolver.resolve("")

                result.shouldBeInstanceOf<PlaybackIntent.NotFound>()
            }
        }

        test("whitespace-only query falls through to search") {
            runTest {
                setup()
                searchRepository.setResults(
                    testSearchHit(id = "book1", name = "Some Book", score = 0.9f),
                )

                val result = resolver.resolve("   ")

                // Whitespace query goes to search, returns whatever search finds
                result.shouldBeInstanceOf<PlaybackIntent.PlayBook>()
            }
        }

        // ========== Confidence Scoring Edge Cases ==========

        test("very low score with no boosts returns NotFound") {
            runTest {
                setup()
                searchRepository.setResults(
                    testSearchHit(id = "book1", name = "Completely Different Title", score = 0.1f),
                )

                val result = resolver.resolve("The Hobbit")

                // Low score + no title match = below ambiguous threshold = NotFound
                result.shouldBeInstanceOf<PlaybackIntent.NotFound>()
            }
        }

        test("author hint boosts confidence") {
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
                result.shouldBeInstanceOf<PlaybackIntent.PlayBook>()
            }
        }

        test("title starts with query boosts confidence") {
            runTest {
                setup()
                searchRepository.setResults(
                    testSearchHit(id = "book1", name = "The Hobbit: An Unexpected Journey", score = 0.5f),
                )

                val result = resolver.resolve("The Hobbit")

                // "The Hobbit: An Unexpected Journey" starts with "the hobbit"
                // Should get TITLE_STARTS_WITH_BOOST + base score
                result.shouldBeInstanceOf<PlaybackIntent.PlayBook>()
            }
        }

        test("single match above ambiguous but below high confidence still plays") {
            runTest {
                setup()
                // Score 0.6f * 0.5 = 0.3 base, + 0.3 title starts with = 0.6 total
                // Above 0.5 (ambiguous) but below 0.8 (high confidence)
                // Single match should still play
                searchRepository.setResults(
                    testSearchHit(id = "book1", name = "The Hobbit", score = 0.6f),
                )

                val result = resolver.resolve("Hobbit")

                result.shouldBeInstanceOf<PlaybackIntent.PlayBook>()
            }
        }

        // ========== Series Navigation Edge Cases ==========

        test("next book at end of series returns null and falls back to search") {
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
                val playBook = result.shouldBeInstanceOf<PlaybackIntent.PlayBook>()
                playBook.bookId shouldBe "other"
            }
        }

        test("book by sequence not found returns null and falls back to search") {
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
                val playBook = result.shouldBeInstanceOf<PlaybackIntent.PlayBook>()
                playBook.bookId shouldBe "other"
            }
        }

        test("ambiguous result has candidates sorted by confidence") {
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

                val ambiguous = result.shouldBeInstanceOf<PlaybackIntent.Ambiguous>()
                (ambiguous.candidates.size >= 2) shouldBe true
                // Candidates should be sorted by confidence (highest first)
                for (i in 0 until ambiguous.candidates.size - 1) {
                    withClue("Candidates should be sorted by confidence descending") {
                        (ambiguous.candidates[i].confidence >= ambiguous.candidates[i + 1].confidence) shouldBe true
                    }
                }
            }
        }

        test("ambiguous result bestGuess is null when top match below threshold") {
            runTest {
                setup()
                // All matches below ambiguous threshold
                searchRepository.setResults(
                    testSearchHit(id = "book1", name = "Something Else", score = 0.3f),
                    testSearchHit(id = "book2", name = "Another Thing", score = 0.2f),
                )

                val result = resolver.resolve("The Hobbit")

                // Both below 0.5 threshold, should return NotFound
                result.shouldBeInstanceOf<PlaybackIntent.NotFound>()
            }
        }
    })
