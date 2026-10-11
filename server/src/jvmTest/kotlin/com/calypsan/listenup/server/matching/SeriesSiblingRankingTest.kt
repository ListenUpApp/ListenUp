package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.server.metadata.spi.FoundBook
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

private const val HOURS_TO_MINUTES = 60L
private const val SCORE_TOLERANCE = 0.005

private const val WAY_OF_KINGS = "The Stormlight Archive: The Way of Kings"
private const val WORDS_OF_RADIANCE = "The Stormlight Archive: Words of Radiance"

private val SUBJECT =
    yourCopyOfPhm().copy(
        title = WAY_OF_KINGS,
        primaryAuthor = "Brandon Sanderson",
        narrators = listOf("Michael Kramer"),
        durationMs = 45 * HOURS_TO_MINUTES * MINUTE_MS,
        chapterCount = null,
        year = null,
        asin = null,
        isbn = null,
    )

/** A catalogue hit by the same author and narrator, of [title] at [hours] long. */
private fun hit(
    key: String,
    title: String,
    hours: Long,
) = MergedHits(
    listOf(
        SourcedHit(
            MetadataProviderId.AUDIBLE,
            FoundBook(
                key = key,
                title = title,
                authors = listOf("Brandon Sanderson"),
                narrators = listOf("Michael Kramer"),
                durationMs = hours * HOURS_TO_MINUTES * MINUTE_MS,
                asin = key,
                region = "us",
            ),
        ),
    ),
)

private fun rank(vararg groups: MergedHits) = CandidateRanker.rank(SUBJECT, "us", groups.toList())

/**
 * What the ranker does with another book of the same series, read by the same narrator at a similar length.
 * These specs record observed behaviour: they are the input to any decision about [CandidateRanker.STRONG_SCORE],
 * not an endorsement of it.
 */
class SeriesSiblingRankingTest :
    FunSpec({
        test("the true edition alone scores 1.00, is Strong and is the best match") {
            val edition = rank(hit("B1", WAY_OF_KINGS, hours = 45)).single()
            edition.score shouldBe (1.0 plusOrMinus SCORE_TOLERANCE)
            edition.tier shouldBe MatchTier.STRONG
            edition.isBest shouldBe true
        }

        test("a sibling within 7% of the length scores 0.86 and is tiered Strong") {
            val sibling = rank(hit("B2", WORDS_OF_RADIANCE, hours = 48)).single()
            sibling.score shouldBe (0.86 plusOrMinus SCORE_TOLERANCE)
            sibling.tier shouldBe MatchTier.STRONG
        }

        test("a sibling within 7% of the length is the best match when the true edition is absent") {
            rank(hit("B2", WORDS_OF_RADIANCE, hours = 48)).single().isBest shouldBe true
        }

        test("a sibling of very different length scores 0.57 and is only a Maybe") {
            val sibling = rank(hit("B3", WORDS_OF_RADIANCE, hours = 30)).single()
            sibling.score shouldBe (0.57 plusOrMinus SCORE_TOLERANCE)
            sibling.tier shouldBe MatchTier.MAYBE
            sibling.isBest shouldBe false
        }

        test("a sibling 51 hours long, 12% longer, scores 0.80 and is still Strong") {
            val sibling = rank(hit("B4", WORDS_OF_RADIANCE, hours = 51)).single()
            sibling.score shouldBe (0.80 plusOrMinus SCORE_TOLERANCE)
            sibling.tier shouldBe MatchTier.STRONG
        }

        test("a sibling 52 hours long, 13% longer, scores 0.79 and falls to Maybe") {
            val sibling = rank(hit("B5", WORDS_OF_RADIANCE, hours = 52)).single()
            sibling.score shouldBe (0.79 plusOrMinus SCORE_TOLERANCE)
            sibling.tier shouldBe MatchTier.MAYBE
        }

        test("with the true edition present it out-ranks both siblings and only it is the best match") {
            val ranked =
                rank(
                    hit("B2", WORDS_OF_RADIANCE, hours = 48),
                    hit("B1", WAY_OF_KINGS, hours = 45),
                    hit("B3", WORDS_OF_RADIANCE, hours = 30),
                )
            ranked.map { candidate ->
                candidate.key.refs
                    .single()
                    .id
            } shouldBe listOf("B1", "B2", "B3")
            ranked.map { it.isBest } shouldBe listOf(true, false, false)
            ranked.map { it.tier } shouldBe listOf(MatchTier.STRONG, MatchTier.STRONG, MatchTier.MAYBE)
        }
    })
