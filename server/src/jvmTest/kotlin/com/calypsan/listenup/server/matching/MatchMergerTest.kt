package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.EditionFormat
import com.calypsan.listenup.server.metadata.spi.FoundBook
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private val AUDIBLE = MetadataProviderId.AUDIBLE
private val HARDCOVER = MetadataProviderId.HARDCOVER
private val ITUNES = MetadataProviderId.ITUNES

private fun phm(
    key: String,
    asin: String? = null,
    isbn: String? = null,
    durationMs: Long? = 970 * MINUTE_MS,
    narrators: List<String> = listOf("Ray Porter"),
    title: String = "Project Hail Mary",
    format: EditionFormat? = EditionFormat.UNABRIDGED,
    viaLink: Boolean = false,
    region: String? = null,
) = FoundBook(
    key = key,
    title = title,
    authors = listOf("Andy Weir"),
    narrators = narrators,
    durationMs = durationMs,
    asin = asin,
    isbn = isbn,
    format = format,
    region = region,
    viaLink = viaLink,
)

private fun merged(vararg hits: SourcedHit) = MatchMerger.merge(hits.toList())

/** Conservative merging (spec, Find step 4): two editions shown apart cost a glance; merged, the wrong one applies. */
class MatchMergerTest :
    FunSpec({
        test("hits sharing an ASIN are one candidate, whatever else differs") {
            merged(
                SourcedHit(AUDIBLE, phm("B1", asin = "B1")),
                SourcedHit(HARDCOVER, phm("427578", asin = "b1", durationMs = null)),
            ) shouldHaveSize 1
        }

        test("hits sharing an ISBN are one candidate") {
            merged(
                SourcedHit(AUDIBLE, phm("B1", isbn = "978-0593135204")),
                SourcedHit(HARDCOVER, phm("427578", isbn = "9780593135204")),
            ) shouldHaveSize 1
        }

        test("same title, author and narrators within two minutes are one candidate") {
            merged(
                SourcedHit(AUDIBLE, phm("B1")),
                SourcedHit(HARDCOVER, phm("427578", durationMs = 970 * MINUTE_MS + 110_000L)),
            ) shouldHaveSize 1
        }

        test("never merged: different narrators, even at the same length") {
            merged(
                SourcedHit(AUDIBLE, phm("B1")),
                SourcedHit(HARDCOVER, phm("427578", narrators = listOf("Full Cast"))),
            ) shouldHaveSize 2
        }

        test("never merged: more than two minutes apart, a missing length, or a different format") {
            merged(SourcedHit(AUDIBLE, phm("B1")), SourcedHit(HARDCOVER, phm("1", durationMs = 970 * MINUTE_MS + 121_000L))) shouldHaveSize
                2
            merged(SourcedHit(AUDIBLE, phm("B1")), SourcedHit(HARDCOVER, phm("2", durationMs = null))) shouldHaveSize 2
            merged(SourcedHit(AUDIBLE, phm("B1")), SourcedHit(HARDCOVER, phm("3", format = EditionFormat.ABRIDGED))) shouldHaveSize 2
        }

        test("never merged: two editions from one catalogue, however alike") {
            merged(SourcedHit(AUDIBLE, phm("B1")), SourcedHit(AUDIBLE, phm("B2"))) shouldHaveSize 2
        }

        test("one catalogue entry found twice is one hit that remembers the link and keeps the searched store") {
            val groups =
                merged(
                    SourcedHit(AUDIBLE, phm("B1", viaLink = true, region = "uk")),
                    SourcedHit(AUDIBLE, phm("B1", region = "us")),
                )
            groups shouldHaveSize 1
            val hit =
                groups
                    .single()
                    .members
                    .single()
                    .book
            hit.viaLink shouldBe true
            hit.region shouldBe "us"
        }

        test("a cover-only hit joins every candidate with its title and author, and no other") {
            val groups =
                merged(
                    SourcedHit(AUDIBLE, phm("B1")),
                    SourcedHit(
                        AUDIBLE,
                        phm("B2", title = "Project Hail Mary [Dramatized Adaptation]", narrators = listOf("Full Cast")),
                    ),
                )
            val cover = SourcedHit(ITUNES, FoundBook(key = "111", title = "Project Hail Mary", authors = listOf("Andy Weir")))
            val withCovers = MatchMerger.attach(groups, listOf(cover))

            withCovers[0].attached.map { it.book.key } shouldBe listOf("111")
            withCovers[1].attached shouldBe emptyList()
        }
    })
