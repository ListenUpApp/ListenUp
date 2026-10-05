package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.BookFindRequest
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FindStrategy
import com.calypsan.listenup.api.dto.match.IdentifierKind
import com.calypsan.listenup.api.dto.match.MetadataSource
import com.calypsan.listenup.api.dto.match.RegionContext
import com.calypsan.listenup.api.dto.match.RegionOrigin
import com.calypsan.listenup.api.dto.match.SearchStep
import com.calypsan.listenup.api.dto.match.SourceStatus
import com.calypsan.listenup.api.dto.match.UnavailableReason
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.FindAvailability
import com.calypsan.listenup.server.metadata.spi.FindKey
import com.calypsan.listenup.server.metadata.spi.FindRole
import com.calypsan.listenup.server.metadata.spi.FindStep
import com.calypsan.listenup.server.metadata.spi.FoundBook
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

private val AUDIBLE = MetadataSource("audible", "Audible")
private val HARDCOVER = MetadataSource("hardcover", "Hardcover")
private val ITUNES = MetadataSource("itunes", "iTunes")
private val US_LIBRARY = ResolvedRegion(MetadataLocale("us"), RegionOrigin.LIBRARY)
private val SUBJECT = yourCopyOfPhm(refs = listOf(ExternalRef("audible", "B08G9PRS1K", "us")))

private fun phm(
    key: String,
    region: String? = null,
    asin: String? = key,
    viaLink: Boolean = false,
) = FoundBook(
    key = key,
    title = "Project Hail Mary",
    authors = listOf("Andy Weir"),
    narrators = listOf("Ray Porter"),
    durationMs = 970 * MINUTE_MS,
    asin = asin,
    region = region,
    viaLink = viaLink,
)

private class Rig {
    val audible = FakeRegionalFindSource(MetadataProviderId.AUDIBLE)
    val hardcover = FakeFindSource(MetadataProviderId.HARDCOVER)
    val itunes = FakeFindSource(MetadataProviderId.ITUNES, findRole = FindRole.ATTACHES)
    val finder = BookFinder(MetadataProviderRegistry(listOf(itunes, hardcover, audible)), EnrichmentRoutes.DEFAULT)

    suspend fun find(
        request: BookFindRequest = BookFindRequest(),
        region: ResolvedRegion = US_LIBRARY,
    ) = finder.find(SUBJECT, request, region)
}

/** Find's fan-out (spec, Find steps 1–6): every source at once, each under its own deadline, each with a typed status. */
class BookFinderTest :
    FunSpec({
        test("every source is asked once, and agreeing hits come back as one candidate found in all three") {
            runTest {
                val rig = Rig()
                rig.audible.answers(listOf(phm("B08G9PRS1K", region = "us", viaLink = true)), setOf(FindStep.LINK, FindStep.TEXT))
                rig.hardcover.answers(listOf(phm("427578", asin = "B08G9PRS1K")))
                rig.itunes.answers(
                    listOf(FoundBook("111", "Project Hail Mary", authors = listOf("Andy Weir"), coverUrl = "https://i/7.jpg")),
                )

                val result = rig.find()

                val best = result.candidates.single()
                best.foundIn.map { it.source } shouldBe listOf(AUDIBLE, HARDCOVER, ITUNES)
                best.isBest shouldBe true
                best.isCurrentLink shouldBe true
                result.sources shouldBe
                    listOf(
                        SourceStatus.Answered(AUDIBLE, 1),
                        SourceStatus.Answered(HARDCOVER, 1),
                        SourceStatus.Answered(ITUNES, 1),
                    )
                result.steps shouldBe listOf(SearchStep.ExistingLink(AUDIBLE), SearchStep.TitleAuthorLength)
                result.yourCopy.chapterCount shouldBe 36
            }
        }

        test("each source gets its own refs, the book's identifiers and the title-and-author query") {
            runTest {
                val rig = Rig()
                rig.find()
                rig.audible.asked
                    .single()
                    .keys shouldBe listOf(FindKey("B08G9PRS1K", "us"))
                val hardcover = rig.hardcover.asked.single()
                hardcover.keys shouldBe emptyList()
                hardcover.asin shouldBe "B08G9PRS1K"
                hardcover.isbn shouldBe "9780593135204"
                hardcover.text shouldBe "Project Hail Mary Andy Weir"
            }
        }

        test("the steps line follows what ran: identifiers, then the person's own query") {
            runTest {
                val rig = Rig()
                rig.hardcover.answers(emptyList(), setOf(FindStep.ASIN, FindStep.ISBN, FindStep.TEXT))
                rig.find(BookFindRequest(query = "  hail mary  ")).steps shouldBe
                    listOf(
                        SearchStep.Identifier(IdentifierKind.ASIN),
                        SearchStep.Identifier(IdentifierKind.ISBN),
                        SearchStep.YourQuery("hail mary"),
                    )
                rig.hardcover.asked
                    .last()
                    .text shouldBe "hail mary"
            }
        }

        test("'Search by title' sends no keys and no identifiers") {
            runTest {
                val rig = Rig()
                rig.find(BookFindRequest(strategy = FindStrategy.TITLE_AUTHOR))
                listOf(rig.audible, rig.hardcover).forEach { source ->
                    val lookup = source.asked.single()
                    lookup.identify shouldBe false
                    lookup.keys shouldBe emptyList()
                    lookup.asin shouldBe null
                    lookup.isbn shouldBe null
                }
            }
        }

        test("a source slower than eight seconds is timed out, and the rest still answer within the deadline") {
            runTest {
                val rig = Rig()
                rig.audible.answers(listOf(phm("B08G9PRS1K", region = "us")))
                rig.hardcover.latency = 9.seconds
                rig.hardcover.answers(listOf(phm("427578")))

                val result = rig.find()

                result.sources[1] shouldBe SourceStatus.TimedOut(HARDCOVER)
                result.candidates
                    .single()
                    .foundIn
                    .map { it.source } shouldBe listOf(AUDIBLE)
                currentTime shouldBe 8_000L
            }
        }

        test("a rate limit keeps its retry-after, or 30 seconds when the source gave none") {
            runTest {
                val rig = Rig()
                rig.hardcover.answer = { AppResult.Failure(MetadataError.ExternalRateLimited(retryAfterSeconds = 45)) }
                rig.itunes.answer = { AppResult.Failure(MetadataError.ExternalRateLimited()) }
                val sources = rig.find().sources
                sources[1] shouldBe SourceStatus.RateLimited(HARDCOVER, 45)
                sources[2] shouldBe SourceStatus.RateLimited(ITUNES, 30)
            }
        }

        test("a failure or a throw is Failed, and never sinks the other sources") {
            runTest {
                val rig = Rig()
                rig.audible.answers(listOf(phm("B08G9PRS1K", region = "us")))
                rig.hardcover.answer = { AppResult.Failure(MetadataError.ExternalUnavailable()) }
                rig.itunes.answer = { error("boom") }
                val result = rig.find()
                result.sources shouldBe
                    listOf(SourceStatus.Answered(AUDIBLE, 1), SourceStatus.Failed(HARDCOVER), SourceStatus.Failed(ITUNES))
                result.candidates.size shouldBe 1
            }
        }

        test("cancellation is never swallowed") {
            runTest {
                val rig = Rig()
                rig.hardcover.answer = { throw CancellationException("caller went away") }
                shouldThrow<CancellationException> { rig.find() }
            }
        }

        test("an unavailable source is reported with its reason, and never asked") {
            runTest {
                val rig = Rig()
                rig.hardcover.availability = FindAvailability.Unavailable(UnavailableReason.NOT_CONFIGURED)
                rig.find().sources[1] shouldBe SourceStatus.Unavailable(HARDCOVER, UnavailableReason.NOT_CONFIGURED)
                rig.hardcover.asked shouldBe emptyList()
            }
        }

        test("a store that answers empty is 'not found in this store', with suggestions") {
            runTest {
                val uk = ResolvedRegion(MetadataLocale("uk"), RegionOrigin.SEARCH_OVERRIDE)
                Rig().find(region = uk).sources[0] shouldBe
                    SourceStatus.NotFoundInStore(AUDIBLE, MetadataLocale("uk"), listOf(MetadataLocale("us"), MetadataLocale("au")))
            }
        }

        test("the region context names the store's source, the store, its origin and the choices") {
            runTest {
                Rig().find().region shouldBe
                    RegionContext(AUDIBLE, MetadataLocale("us"), RegionOrigin.LIBRARY, MetadataLocale.SUPPORTED)
            }
        }

        test("a retry re-asks only the source that failed") {
            runTest {
                val rig = Rig()
                rig.hardcover.answer = { AppResult.Failure(MetadataError.ExternalUnavailable()) }
                rig.find()
                rig.hardcover.answers(listOf(phm("427578")))

                rig.find().sources[1] shouldBe SourceStatus.Answered(HARDCOVER, 1)
                rig.audible.asked.size shouldBe 1
                rig.itunes.asked.size shouldBe 1
                rig.hardcover.asked.size shouldBe 2
            }
        }

        test("a source the operator didn't route to Find's domain is never asked") {
            runTest {
                val rig = Rig()
                val routes = EnrichmentRoutes.parse(order = null, routes = "core=audible")
                val finder = BookFinder(MetadataProviderRegistry(listOf(rig.audible, rig.hardcover, rig.itunes)), routes)

                finder.find(SUBJECT, BookFindRequest(), US_LIBRARY)

                rig.hardcover.asked shouldBe emptyList()
                rig.itunes.asked.size shouldBe 1
            }
        }
    })
