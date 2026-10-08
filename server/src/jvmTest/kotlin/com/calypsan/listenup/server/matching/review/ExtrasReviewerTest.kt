package com.calypsan.listenup.server.matching.review

import com.calypsan.listenup.api.dto.match.ChapterNameChange
import com.calypsan.listenup.api.dto.match.ChapterNamesReview
import com.calypsan.listenup.api.dto.match.CurrentCover
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.server.metadata.spi.ChapterListMeta
import com.calypsan.listenup.server.metadata.spi.ChapterMeta
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private val SIZES = mapOf("https://a/big.jpg" to (2400 to 2400), "https://a/small.jpg" to (500 to 500), "https://h/c.jpg" to (800 to 1200))

private suspend fun covers(
    candidateProviders: Set<String>,
    setByHand: Boolean = false,
    tiles: Boolean = true,
) = CoverReviewer.review(
    book =
        yourBook().copy(
            cover = CoverPayload(CoverSource.EMBEDDED, "h0"),
            fieldProvenance =
                if (setByHand) mapOf(BookField.COVER to FieldProvenance(FieldSourceKind.USER)) else emptyMap(),
        ),
    covers =
        if (tiles) {
            mapOf(
                AUDIBLE to
                    listOf(
                        CoverMeta("https://a/small.jpg", sourceKey = "A"),
                        CoverMeta("https://a/x.jpg", "https://a/big.jpg", "A"),
                    ),
                HARDCOVER to listOf(CoverMeta("https://h/c.jpg", sourceKey = "1"), CoverMeta("https://a/big.jpg", sourceKey = "1")),
            )
        } else {
            emptyMap()
        },
    routes = EnrichmentRoutes.DEFAULT,
    candidateProviders = candidateProviders,
    probe = { SIZES[it] },
)

private fun chapter(
    title: String,
    start: Long,
) = BookChapterPayload(id = "c$start", title = title, duration = 1_000, startTime = start)

class ExtrasReviewerTest :
    FunSpec({
        test("cover tiles are ordered by route then by size, one tile per URL, with your cover beside them") {
            runTest {
                val result = covers(setOf("audible"))
                result.review.options.map { it.url } shouldBe listOf("https://a/big.jpg", "https://a/small.jpg", "https://h/c.jpg")
                result.review.options
                    .first()
                    .width shouldBe 2400
                result.review.current shouldBe CurrentCover("h0", setByHand = false)
            }
        }

        test("the default cover is the first tile from a source that found this candidate") {
            runTest {
                val result = covers(setOf("hardcover"))
                result.review.defaultChoice shouldBe
                    ImageChoice.Candidate(
                        result.review.options
                            .last()
                            .optionId,
                    )
            }
        }

        test("with no tile from the candidate's sources, the default is the first tile") {
            runTest {
                val result = covers(setOf("itunes"))
                result.review.defaultChoice shouldBe
                    ImageChoice.Candidate(
                        result.review.options
                            .first()
                            .optionId,
                    )
            }
        }

        test("a cover set by hand is kept by default (decision 6)") {
            runTest { covers(setOf("audible"), setByHand = true).review.defaultChoice shouldBe ImageChoice.KeepCurrent }
        }

        test("no tiles means keep current") {
            runTest { covers(setOf("audible"), tiles = false).review.defaultChoice shouldBe ImageChoice.KeepCurrent }
        }

        test("suggestions exclude labels you have, merge sources case-insensitively, in route order") {
            runTest {
                val (review, reviewed) =
                    LabelReviewer.review(
                        yours = listOf("Science Fiction"),
                        byProvider =
                            mapOf(
                                AUDIBLE to listOf("science fiction", "Space Opera"),
                                HARDCOVER to listOf("space opera", "Humor"),
                            ),
                        order = listOf(AUDIBLE, HARDCOVER),
                        identity = { s, yours -> yours.any { it.equals(s, ignoreCase = true) } },
                    )
                review.yours shouldBe listOf("Science Fiction")
                review.suggested.map { it.label } shouldBe listOf("Space Opera", "Humor")
                review.suggested
                    .first()
                    .sources
                    .map { it.label } shouldBe listOf("Audible", "Hardcover")
                reviewed.first().providers shouldBe listOf(AUDIBLE, HARDCOVER)
            }
        }

        test("mood identity matches by slug") {
            runTest {
                MoodLabelIdentity.same("Feel Good", listOf("feel-good")) shouldBe true
                MoodLabelIdentity.same("Tense", listOf("feel-good")) shouldBe false
            }
        }

        test("chapter rows list only names that differ, in start-time order") {
            val yours = listOf(chapter("Chapter 2", 2_000), chapter("Chapter 1", 0), chapter("Chapter 3", 4_000))
            val theirs =
                ChapterListMeta(
                    listOf(ChapterMeta("Opening", 0), ChapterMeta("Chapter 2", 2_000), ChapterMeta(null, 4_000)),
                    accurate = true,
                )
            val (review, reviewed) = ChapterNamesReviewer.review(yours, AUDNEXUS to theirs)
            review shouldBe
                ChapterNamesReview.Available(
                    source = (review as ChapterNamesReview.Available).source,
                    rows = listOf(ChapterNameChange(0, "Chapter 1", "Opening")),
                    unchangedCount = 2,
                )
            (review as ChapterNamesReview.Available).source.label shouldBe "Audible"
            reviewed!!.names shouldBe listOf("Opening", "Chapter 2", null)
        }

        test("chapter counts that differ are a mismatch; no source is unavailable") {
            val (mismatch, none) =
                ChapterNamesReviewer.review(
                    listOf(chapter("A", 0)),
                    AUDIBLE to ChapterListMeta(emptyList<ChapterMeta>() + ChapterMeta("x", 0) + ChapterMeta("y", 1), true),
                )
            (mismatch as ChapterNamesReview.CountMismatch).let { it.yours to it.theirs } shouldBe (1 to 2)
            none.shouldBeNull()
            ChapterNamesReviewer.review(emptyList(), null).first shouldBe ChapterNamesReview.Unavailable
        }

        test("matching chapter names give no rows") {
            val (review, _) =
                ChapterNamesReviewer.review(
                    listOf(chapter("A", 0)),
                    AUDIBLE to ChapterListMeta(listOf(ChapterMeta("A", 0)), true),
                )
            (review as ChapterNamesReview.Available).rows.shouldBeEmpty()
        }
    })
