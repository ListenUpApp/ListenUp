package com.calypsan.listenup.client.design.timeline

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private const val BOOK_MS = 234_000_000L

/**
 * [chapterDensity] — what the minimap actually draws.
 *
 * 311 markers across 1200px is one every four pixels: drawn literally they are a grey smear that
 * says nothing about the book. Aggregating them into buckets and shading by count turns the same
 * data into structure you can read at a glance — where the short chapters cluster, where the long
 * ones sit — which is the only reason the minimap earns its space.
 */
class ChapterDensityTest :
    FunSpec({

        fun evenlySpaced(count: Int): List<Long> = List(count) { it * (BOOK_MS / count) }

        test("buckets come back at the requested resolution, whatever the chapter count") {
            chapterDensity(evenlySpaced(311), BOOK_MS, bucketCount = 90) shouldHaveSize 90
            chapterDensity(evenlySpaced(3), BOOK_MS, bucketCount = 90) shouldHaveSize 90
        }

        test("an evenly-chaptered book reads as flat") {
            val d = chapterDensity(evenlySpaced(180), BOOK_MS, bucketCount = 90)

            withClue("every bucket holds the same couple of chapters, so nothing stands out") {
                d.distinct() shouldHaveSize 1
            }
        }

        test("a cluster of short chapters reads as a peak, and empty stretches as troughs") {
            // Every chapter inside the first tenth of the book.
            val clustered = List(60) { it * (BOOK_MS / 600) }

            val d = chapterDensity(clustered, BOOK_MS, bucketCount = 100)

            withClue("the cluster saturates") { d.first() shouldBe 1f }
            withClue("the empty tail is empty, not merely faint") { d.last() shouldBe 0f }
        }

        test("density is normalised, so the busiest bucket is always full height") {
            // Absolute counts are meaningless to draw against: 6-per-bucket is dense for one book
            // and sparse for another. Only the shape within THIS book means anything.
            val sparse = chapterDensity(evenlySpaced(20), BOOK_MS, bucketCount = 40)
            val dense = chapterDensity(evenlySpaced(2000), BOOK_MS, bucketCount = 40)

            sparse.max() shouldBe 1f
            dense.max() shouldBe 1f
        }

        test("a book with no chapters is flat zero rather than a crash or a full bar") {
            val d = chapterDensity(emptyList(), BOOK_MS, bucketCount = 30)

            d shouldHaveSize 30
            withClue("an unchaptered book has no structure to show — and the editor still opens") {
                d.all { it == 0f } shouldBe true
            }
        }

        test("a zero-duration book does not divide by zero") {
            val d = chapterDensity(listOf(0L), bookDurationMs = 0L, bucketCount = 10)

            d shouldHaveSize 10
            d.all { it.isNaN() } shouldBe false
        }

        test("a chapter exactly at the end lands in the last bucket, not past it") {
            // The off-by-one that would throw on the one book whose final chapter starts at the
            // very last millisecond.
            val d = chapterDensity(listOf(0L, BOOK_MS), BOOK_MS, bucketCount = 10)

            d.first() shouldBe 1f
            d.last() shouldBe 1f
        }

        test("chapters outside the book are ignored rather than distorting the shape") {
            // Defensive: a bad drift apply could momentarily produce one. It should not smear the
            // whole minimap by inflating the maximum it normalises against.
            val d = chapterDensity(listOf(-5_000L, 0L, BOOK_MS + 9_000L), BOOK_MS, bucketCount = 10)

            d.first() shouldBe 1f
            d.drop(1).all { it == 0f } shouldBe true
        }
    })
