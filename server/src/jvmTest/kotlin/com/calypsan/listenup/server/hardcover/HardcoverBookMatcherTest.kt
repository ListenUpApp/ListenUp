package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest

private val HAIL_MARY_AUDIO =
    FakeHardcoverLibrary.Edition(
        id = 9_001L,
        bookId = 427_578L,
        title = "Project Hail Mary",
        authors = listOf("Andy Weir"),
        asin = "B08G9RZBTT",
        readingFormatId = 2,
        defaultAudioEditionId = 9_001L,
    )
private val HAIL_MARY_PAPERBACK =
    HAIL_MARY_AUDIO.copy(id = 31_415L, asin = null, isbn13 = "9780593135204", readingFormatId = 1)

// Hardcover's real Project Hail Mary (live schema, 2026-09-30): the ASIN edition is NOT the book's
// default audiobook edition — a book can carry several audiobook editions.
private const val LIVE_ASIN_EDITION = 32_879_148L
private const val LIVE_DEFAULT_AUDIO_EDITION = 31_878_554L
private const val LIVE_ISBN_EDITION = 3_274_049L

private fun matcherOver(vararg editions: FakeHardcoverLibrary.Edition): Pair<HardcoverBookMatcher, FakeHardcoverLibrary> {
    val hardcover = FakeHardcoverLibrary()
    editions.forEach(hardcover::addEdition)
    return HardcoverBookMatcher(hardcover.client(), NoWaitRateLimiter()) to hardcover
}

/** B4: the first confident hit wins, in ASIN → ISBN → title order, and nothing uncertain is ever guessed. */
class HardcoverBookMatcherTest :
    FunSpec({

        test("an ASIN finds the exact audiobook edition and stops there") {
            runTest {
                val (matcher, hardcover) = matcherOver(HAIL_MARY_AUDIO)
                matcher.match("hc_at_1", BookIdentity(asin = "B08G9RZBTT", isbn = "9780593135204", title = "Project Hail Mary")) shouldBe
                    HardcoverCall.Ok(HardcoverMatch(427_578L, 9_001L, HardcoverMatchMethod.ASIN))
                hardcover.operations shouldBe listOf("edition_by_asin")
            }
        }

        test("an ASIN hit keeps its own edition, not the book's default audiobook edition") {
            runTest {
                val (matcher, _) =
                    matcherOver(HAIL_MARY_AUDIO.copy(id = LIVE_ASIN_EDITION, defaultAudioEditionId = LIVE_DEFAULT_AUDIO_EDITION))
                matcher.match("hc_at_1", BookIdentity(asin = "B08G9RZBTT", title = "Project Hail Mary")) shouldBe
                    HardcoverCall.Ok(HardcoverMatch(427_578L, LIVE_ASIN_EDITION, HardcoverMatchMethod.ASIN))
            }
        }

        test("an ISBN of a paperback records the book and prefers its audiobook edition") {
            runTest {
                val (matcher, _) = matcherOver(HAIL_MARY_PAPERBACK)
                matcher.match("hc_at_1", BookIdentity(asin = "B000UNKNOWN", isbn = "9780593135204", title = "Project Hail Mary")) shouldBe
                    HardcoverCall.Ok(HardcoverMatch(427_578L, 9_001L, HardcoverMatchMethod.ISBN))
            }
        }

        test("an ISBN of a Listened (format 2) edition keeps that edition") {
            runTest {
                val (matcher, _) =
                    matcherOver(
                        HAIL_MARY_PAPERBACK.copy(
                            id = LIVE_ISBN_EDITION,
                            readingFormatId = 2,
                            defaultAudioEditionId = LIVE_DEFAULT_AUDIO_EDITION,
                        ),
                    )
                matcher.match("hc_at_1", BookIdentity(isbn = "9780593135204", title = "Project Hail Mary")) shouldBe
                    HardcoverCall.Ok(HardcoverMatch(427_578L, LIVE_ISBN_EDITION, HardcoverMatchMethod.ISBN))
            }
        }

        test("an ISBN of a Both (format 3) edition is not an audiobook: the book's audiobook edition wins") {
            runTest {
                val (matcher, _) =
                    matcherOver(
                        HAIL_MARY_PAPERBACK.copy(
                            id = LIVE_ISBN_EDITION,
                            readingFormatId = 3,
                            defaultAudioEditionId = LIVE_DEFAULT_AUDIO_EDITION,
                        ),
                    )
                matcher.match("hc_at_1", BookIdentity(isbn = "9780593135204", title = "Project Hail Mary")) shouldBe
                    HardcoverCall.Ok(HardcoverMatch(427_578L, LIVE_DEFAULT_AUDIO_EDITION, HardcoverMatchMethod.ISBN))
            }
        }

        test("an ISBN whose book has no audiobook edition keeps the matched edition") {
            runTest {
                val (matcher, _) = matcherOver(HAIL_MARY_PAPERBACK.copy(defaultAudioEditionId = null))
                matcher.match("hc_at_1", BookIdentity(isbn = "9780593135204", title = "Project Hail Mary")) shouldBe
                    HardcoverCall.Ok(HardcoverMatch(427_578L, 31_415L, HardcoverMatchMethod.ISBN))
            }
        }

        test("a single title-and-author hit is a SEARCH match") {
            runTest {
                val (matcher, _) = matcherOver(HAIL_MARY_AUDIO.copy(asin = null))
                matcher.match("hc_at_1", BookIdentity(title = "Project Hail Mary", primaryAuthor = "Andy Weir")) shouldBe
                    HardcoverCall.Ok(HardcoverMatch(427_578L, 9_001L, HardcoverMatchMethod.SEARCH))
            }
        }

        test("the one real book among author-less duplicates is a SEARCH match") {
            runTest {
                val real = HAIL_MARY_AUDIO.copy(asin = null, defaultAudioEditionId = LIVE_DEFAULT_AUDIO_EDITION)
                val duplicates = (1..4).map { n -> real.copy(id = 70_000L + n, bookId = 80_000L + n, authors = emptyList()) }
                val (matcher, _) = matcherOver(real, *duplicates.toTypedArray())
                matcher.match("hc_at_1", BookIdentity(title = "Project Hail Mary", primaryAuthor = "Andy Weir")) shouldBe
                    HardcoverCall.Ok(HardcoverMatch(427_578L, LIVE_DEFAULT_AUDIO_EDITION, HardcoverMatchMethod.SEARCH))
            }
        }

        test("any credited author may be the one that overlaps") {
            runTest {
                val (matcher, _) = matcherOver(HAIL_MARY_AUDIO.copy(asin = null, authors = listOf("Ray Porter", "Andy Weir")))
                matcher.match("hc_at_1", BookIdentity(title = "Project Hail Mary", primaryAuthor = "Andy Weir")) shouldBe
                    HardcoverCall.Ok(HardcoverMatch(427_578L, 9_001L, HardcoverMatchMethod.SEARCH))
            }
        }

        test("two confident title hits are ambiguous: no match, never a guess") {
            runTest {
                val first = FakeHardcoverLibrary.Edition(1L, 229_211L, "The Best Christmas Pageant Ever", listOf("Barbara Robinson"))
                val duplicate = first.copy(id = 2L, bookId = 317_024L)
                val (matcher, _) = matcherOver(first, duplicate)
                matcher.match(
                    "hc_at_1",
                    BookIdentity(title = "The Best Christmas Pageant Ever", primaryAuthor = "Barbara Robinson"),
                ) shouldBe
                    HardcoverCall.Ok(null)
            }
        }

        test("Dungeon Crawler Carl 4: the real record beats its one-reader duplicate and the author-less copies") {
            runTest {
                val real =
                    FakeHardcoverLibrary.Edition(
                        id = 31_860_587L,
                        bookId = 446_722L,
                        title = "The Gate of the Feral Gods",
                        authors = listOf("Matt Dinniman"),
                        defaultAudioEditionId = 31_860_587L,
                        usersReadCount = 3_895,
                    )
                val oneReaderDuplicate = real.copy(id = 1L, bookId = 2_664_448L, defaultAudioEditionId = null, usersReadCount = 1)
                val authorless =
                    (1..3).map { n -> real.copy(id = 10L + n, bookId = 90_000L + n, authors = emptyList(), usersReadCount = 0) }
                val (matcher, _) = matcherOver(oneReaderDuplicate, real, *authorless.toTypedArray())
                matcher.match("hc_at_1", BookIdentity(title = "The Gate of the Feral Gods", primaryAuthor = "Matt Dinniman")) shouldBe
                    HardcoverCall.Ok(HardcoverMatch(446_722L, 31_860_587L, HardcoverMatchMethod.SEARCH))
            }
        }

        test("the only confident hit with an audiobook edition wins, however few its readers") {
            runTest {
                val printOnly = HAIL_MARY_AUDIO.copy(asin = null, defaultAudioEditionId = null, usersReadCount = 12)
                val audio = printOnly.copy(id = 9_002L, bookId = 500_001L, defaultAudioEditionId = 9_002L, usersReadCount = 10)
                val (matcher, _) = matcherOver(printOnly, audio)
                matcher.match("hc_at_1", BookIdentity(title = "Project Hail Mary", primaryAuthor = "Andy Weir")) shouldBe
                    HardcoverCall.Ok(HardcoverMatch(500_001L, 9_002L, HardcoverMatchMethod.SEARCH))
            }
        }

        test("twenty times the runner-up's readers, and at least fifty, is a clear winner") {
            runTest {
                val runnerUp = HAIL_MARY_AUDIO.copy(asin = null, usersReadCount = 50)
                val popular = runnerUp.copy(id = 9_002L, bookId = 500_001L, defaultAudioEditionId = 9_002L, usersReadCount = 1_000)
                val (matcher, _) = matcherOver(runnerUp, popular)
                matcher.match("hc_at_1", BookIdentity(title = "Project Hail Mary", primaryAuthor = "Andy Weir")) shouldBe
                    HardcoverCall.Ok(HardcoverMatch(500_001L, 9_002L, HardcoverMatchMethod.SEARCH))
            }
        }

        test("two comparable audiobook records are ambiguous: no match, never a guess") {
            runTest {
                val first = HAIL_MARY_AUDIO.copy(asin = null, usersReadCount = 400)
                val second = first.copy(id = 9_002L, bookId = 500_001L, defaultAudioEditionId = 9_002L, usersReadCount = 300)
                val (matcher, _) = matcherOver(first, second)
                matcher.match("hc_at_1", BookIdentity(title = "Project Hail Mary", primaryAuthor = "Andy Weir")) shouldBe
                    HardcoverCall.Ok(null)
            }
        }

        test("a lead by ratio alone is not a winner under fifty readers") {
            runTest {
                val leader = HAIL_MARY_AUDIO.copy(asin = null, usersReadCount = 40)
                val trailer = leader.copy(id = 9_002L, bookId = 500_001L, defaultAudioEditionId = 9_002L, usersReadCount = 1)
                val (matcher, _) = matcherOver(leader, trailer)
                matcher.match("hc_at_1", BookIdentity(title = "Project Hail Mary", primaryAuthor = "Andy Weir")) shouldBe
                    HardcoverCall.Ok(null)
            }
        }

        test("a single confident hit matches with no readers and no audiobook edition") {
            runTest {
                val (matcher, _) = matcherOver(HAIL_MARY_AUDIO.copy(asin = null, defaultAudioEditionId = null, usersReadCount = 0))
                matcher.match("hc_at_1", BookIdentity(title = "Project Hail Mary", primaryAuthor = "Andy Weir")) shouldBe
                    HardcoverCall.Ok(HardcoverMatch(427_578L, null, HardcoverMatchMethod.SEARCH))
            }
        }

        test("a title hit by a different author is no match") {
            runTest {
                val (matcher, _) = matcherOver(HAIL_MARY_AUDIO.copy(asin = null, authors = listOf("Someone Else")))
                matcher.match("hc_at_1", BookIdentity(title = "Project Hail Mary", primaryAuthor = "Andy Weir")) shouldBe
                    HardcoverCall.Ok(null)
            }
        }

        test("a book with no author never reaches the title step") {
            runTest {
                val (matcher, hardcover) = matcherOver(HAIL_MARY_AUDIO.copy(asin = null))
                matcher.match("hc_at_1", BookIdentity(title = "Project Hail Mary")) shouldBe HardcoverCall.Ok(null)
                hardcover.operations shouldBe emptyList()
            }
        }

        test("an outage is a failure, not NEEDS_MATCH") {
            runTest {
                val (matcher, hardcover) = matcherOver(HAIL_MARY_AUDIO)
                hardcover.failNext(FakeReply(HttpStatusCode.ServiceUnavailable))
                matcher.match("hc_at_1", BookIdentity(asin = "B08G9RZBTT", title = "Project Hail Mary")) shouldBe
                    HardcoverCall.Throttled(null)
            }
        }
    })
