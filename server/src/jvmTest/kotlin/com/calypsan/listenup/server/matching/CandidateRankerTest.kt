package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.EditionFormat
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.MatchReason
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.server.metadata.spi.FoundBook
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe

private fun audible(
    key: String,
    minutes: Long = 970,
    narrators: List<String> = listOf("Ray Porter"),
    title: String = "Project Hail Mary",
    chapters: Int? = null,
    region: String = "us",
    format: EditionFormat? = EditionFormat.UNABRIDGED,
    viaLink: Boolean = false,
) = MergedHits(
    listOf(
        SourcedHit(
            MetadataProviderId.AUDIBLE,
            FoundBook(
                key = key,
                title = title,
                authors = listOf("Andy Weir"),
                narrators = narrators,
                durationMs = minutes * MINUTE_MS,
                releaseDate = "2021-05-04",
                format = format,
                asin = key,
                coverUrl = "https://img/$key.jpg",
                region = region,
                chapterCount = chapters,
                viaLink = viaLink,
            ),
        ),
    ),
)

private fun rank(vararg groups: MergedHits) = CandidateRanker.rank(yourCopyOfPhm(), "us", groups.toList())

/** Ranking against your copy (spec, Find step 5): tiers, best match, order and typed reasons. */
class CandidateRankerTest :
    FunSpec({
        test("the same narrator, length and chapter count is a Strong best match, its reasons in order") {
            val best = rank(audible("B1", chapters = 36, viaLink = true)).single()
            best.tier shouldBe MatchTier.STRONG
            best.isBest shouldBe true
            best.isCurrentLink shouldBe true
            best.reasons shouldBe listOf(MatchReason.SameNarrator, MatchReason.SameLength, MatchReason.SameChapterCount(36))
            best.key.refs shouldBe listOf(ExternalRef("audible", "B1", "us"))
            best.year shouldBe 2021
        }

        test("a dramatisation with other narrators, much shorter, is a Maybe that leads with what differs") {
            val drama =
                rank(
                    audible(
                        "B9",
                        minutes = 584,
                        narrators = listOf("Full Cast"),
                        title = "Project Hail Mary [Dramatized Adaptation]",
                        format = EditionFormat.DRAMATIZED,
                    ),
                ).single()
            drama.tier shouldBe MatchTier.MAYBE
            drama.isBest shouldBe false
            drama.reasons shouldBe
                listOf(
                    MatchReason.DifferentNarrators,
                    MatchReason.LengthDiffers(-386),
                    MatchReason.DifferentEdition(EditionFormat.DRAMATIZED),
                )
        }

        test("length: within 30 s is the same, up to 5 min is 'within', beyond is the signed difference") {
            rank(audible("B1", minutes = 971)).single().reasons[1] shouldBe MatchReason.LengthWithin(1)
            rank(audible("B1", minutes = 975)).single().reasons[1] shouldBe MatchReason.LengthWithin(5)
            rank(audible("B1", minutes = 1000)).single().reasons shouldBe
                listOf(MatchReason.SameNarrator, MatchReason.LengthDiffers(30))
        }

        test("a candidate found only in another store says so") {
            rank(audible("B1", region = "uk")).single().reasons.last() shouldBe MatchReason.DifferentStore(MetadataLocale("uk"))
        }

        test("Strong ranks above Maybe, then by score; the current link is labelled, not pinned") {
            val ranked = rank(audible("LINK", minutes = 600, viaLink = true), audible("NEAR", minutes = 975), audible("EXACT"))
            ranked.map {
                it.key.refs
                    .single()
                    .id
            } shouldBe listOf("EXACT", "NEAR", "LINK")
            ranked.map { it.isBest } shouldBe listOf(true, false, false)
            ranked.last().tier shouldBe MatchTier.MAYBE
            ranked.last().isCurrentLink shouldBe true
        }

        test("the tier line sits at 0.8") {
            rank(audible("B1")).single().score shouldBeGreaterThanOrEqual CandidateRanker.STRONG_SCORE
            rank(audible("B1", minutes = 700, narrators = listOf("Someone Else"))).single().score shouldBeLessThan
                CandidateRanker.STRONG_SCORE
        }

        test("a candidate with no length evidence is never Strong, and says its length is unknown") {
            val lengthless =
                MergedHits(
                    listOf(
                        SourcedHit(
                            MetadataProviderId.HARDCOVER,
                            FoundBook("427578", "Project Hail Mary", authors = listOf("Andy Weir")),
                        ),
                    ),
                )
            val ranked = rank(audible("B1", minutes = 971), lengthless)

            ranked.map {
                it.key.refs
                    .single()
                    .provider
            } shouldBe listOf("audible", "hardcover")
            ranked[0].tier shouldBe MatchTier.STRONG
            ranked[0].isBest shouldBe true
            ranked[1].tier shouldBe MatchTier.MAYBE
            ranked[1].reasons.first() shouldBe MatchReason.LengthUnknown
        }

        test("an edition from another store with the same narrator and length stays Strong, saying so") {
            val other = rank(audible("B1", region = "uk")).single()
            other.tier shouldBe MatchTier.STRONG
            other.reasons shouldBe
                listOf(MatchReason.SameNarrator, MatchReason.SameLength, MatchReason.DifferentStore(MetadataLocale("uk")))
        }

        test("a merged candidate lists every source it was found in, and every ref in its key") {
            val merged =
                MergedHits(
                    members =
                        audible("B1").members +
                            SourcedHit(
                                MetadataProviderId.HARDCOVER,
                                FoundBook("427578", "Project Hail Mary", authors = listOf("Andy Weir")),
                            ),
                    attached =
                        listOf(
                            SourcedHit(
                                MetadataProviderId.ITUNES,
                                FoundBook("111", "Project Hail Mary", coverUrl = "https://itunes/7000.jpg"),
                            ),
                        ),
                )
            val candidate = rank(merged).single()
            candidate.foundIn.map { it.source.label } shouldBe listOf("Audible", "Hardcover", "iTunes")
            candidate.key.refs.map { it.provider } shouldBe listOf("audible", "hardcover", "itunes")
            candidate.coverUrl shouldBe "https://img/B1.jpg"
        }
    })
