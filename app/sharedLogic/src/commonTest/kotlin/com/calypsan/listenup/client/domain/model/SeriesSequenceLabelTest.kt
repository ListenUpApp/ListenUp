package com.calypsan.listenup.client.domain.model

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * The one rendering of a series position, at the model that owns it.
 *
 * A position is a `Double` so a 1.5 interquel can exist and so books sort correctly. Nothing a
 * person reads should ever see that `Double`: printed directly it is `"1.0"`, and two separate
 * screens shipped "Book 1.0" by interpolating it. Those were fixed one at a time before; this
 * property is the fix that makes forgetting impossible instead of merely unlikely, so it is worth
 * pinning here rather than only through the screens that happen to use it today.
 */
class SeriesSequenceLabelTest :
    FunSpec({

        fun membership(sequence: Double?) = BookSeries(seriesId = "s1", seriesName = "Mistborn", sequence = sequence)

        test("a whole position loses its decimal tail") {
            membership(1.0).sequenceLabel shouldBe "1"
            membership(10.0).sequenceLabel shouldBe "10"
            membership(0.0).sequenceLabel shouldBe "0"
        }

        test("a fractional position keeps it") {
            membership(1.5).sequenceLabel shouldBe "1.5"
            membership(0.5).sequenceLabel shouldBe "0.5"
        }

        test("an unnumbered membership has no label") {
            membership(null).sequenceLabel.shouldBeNull()
        }

        test("the raw Double stays available, because sorting still needs a number") {
            // The reason the label is a separate property rather than a replacement: "10" sorts
            // before "9" as text, and a series that jumps from 9 to 10 is not unusual.
            withClue("the label is for reading; ordering must not go through strings") {
                listOf(membership(10.0), membership(9.0), membership(1.5))
                    .sortedBy { it.sequence }
                    .map { it.sequenceLabel } shouldBe listOf("1.5", "9", "10")
            }
        }

        test("a book's summary label reads from its first membership") {
            val book =
                TestSummary(
                    series =
                        listOf(
                            membership(2.0),
                            membership(7.5),
                        ),
                )

            book.seriesSequenceLabel shouldBe "2"
            book.seriesSequence shouldBe 2.0
        }

        test("a standalone book has no position label") {
            TestSummary(series = emptyList()).seriesSequenceLabel.shouldBeNull()
        }

        test("the full series title uses the label, never the raw number") {
            TestSummary(series = listOf(membership(1.0))).fullSeriesTitle shouldBe "Mistborn #1"
            TestSummary(series = listOf(membership(1.5))).fullSeriesTitle shouldBe "Mistborn #1.5"
        }

        test("a series title with no position is just the series name") {
            TestSummary(series = listOf(membership(null))).fullSeriesTitle shouldBe "Mistborn"
        }
    })

/** Minimal [BookSummaryFields] stand-in — only the series fields matter here. */
private data class TestSummary(
    override val series: List<BookSeries>,
    override val coverPath: String? = null,
    override val authors: List<BookContributor> = emptyList(),
    override val narrators: List<BookContributor> = emptyList(),
    override val duration: Long = 0L,
) : BookSummaryFields
