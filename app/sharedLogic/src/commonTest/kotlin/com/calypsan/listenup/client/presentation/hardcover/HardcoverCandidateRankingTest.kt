package com.calypsan.listenup.client.presentation.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val SUMMARY = HardcoverBookCandidate(1L, null, "Project Hail Mary: A Novel (Summary)", listOf("Quick Reads"), 2022, 0)
private val REAL = HardcoverBookCandidate(427_578L, 9_001L, "Project Hail Mary", listOf("Andy Weir", "Ray Porter"), 2021, 8_107)
private val OTHER = HardcoverBookCandidate(3L, null, "Hail Mary", listOf("Someone Else"), 1999, 12)

/** Batch A's live finding: Hardcover can rank summaries above the real book, so the author decides. */
class HardcoverCandidateRankingTest :
    FunSpec({

        test("candidates sharing an author with the book come first, otherwise in Hardcover's order") {
            rankCandidates(listOf(SUMMARY, OTHER, REAL), bookAuthors = listOf("Andy Weir")).map { it.hcBookId } shouldBe
                listOf(427_578L, 1L, 3L)
        }

        test("an author matches whatever the case, spacing or punctuation") {
            rankCandidates(listOf(REAL), bookAuthors = listOf("andy  weir.")).single().sharesAuthor shouldBe true
        }

        test("a book with no authors keeps Hardcover's order and marks nothing") {
            rankCandidates(listOf(SUMMARY, REAL), bookAuthors = emptyList()).map { it.sharesAuthor } shouldBe listOf(false, false)
        }

        test("a row carries what tells the real book from a summary") {
            rankCandidates(listOf(REAL), bookAuthors = listOf("Andy Weir")).single() shouldBe
                HardcoverCandidateRow(
                    hcBookId = 427_578L,
                    hcEditionId = 9_001L,
                    title = "Project Hail Mary",
                    authors = listOf("Andy Weir", "Ray Porter"),
                    releaseYear = 2021,
                    ratingsCount = 8_107,
                    hasAudiobookEdition = true,
                    sharesAuthor = true,
                )
        }
    })
