package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.InLibrary
import com.calypsan.listenup.api.dto.match.MetadataSource
import com.calypsan.listenup.api.dto.match.PersonFindRequest
import com.calypsan.listenup.api.dto.match.PersonSearchStep
import com.calypsan.listenup.api.dto.match.RoleCoverage
import com.calypsan.listenup.api.dto.match.SourceStatus
import com.calypsan.listenup.api.dto.match.UnavailableReason
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.FindAvailability
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.metadata.spi.PersonStep
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

private val AUDIBLE = MetadataSource("audible", "Audible")
private val HARDCOVER = MetadataSource("hardcover", "Hardcover")
private val US = MetadataLocale("us")

private class PeopleRig {
    val audnexus = FakePeopleSource(MetadataProviderId.AUDNEXUS, setOf(ContributorRole.AUTHOR))
    val hardcover = FakePeopleSource(MetadataProviderId.HARDCOVER, setOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR))
    val finder = PeopleFinder(MetadataProviderRegistry(listOf(hardcover, audnexus)), EnrichmentRoutes.DEFAULT)
}

/** People Find's fan-out: role routing, deadlines, typed statuses, the steps line, coverage and the no-profile path. */
class PeopleFinderTest :
    FunSpec({
        test("a narrator search never asks Audnexus, and says Audible has no narrator profiles") {
            runTest {
                val rig = PeopleRig()
                rig.hardcover.answers(listOf(person("250716", credited = setOf("phm"))), setOf(PersonStep.NAME, PersonStep.VIA_BOOKS))

                val result = rig.finder.find(porterSubject(), PersonFindRequest(ContributorRole.NARRATOR), US)

                rig.audnexus.asked shouldBe emptyList()
                result.coverage shouldBe listOf(RoleCoverage(AUDIBLE, false), RoleCoverage(HARDCOVER, true))
                result.sources shouldBe
                    listOf(
                        SourceStatus.Unavailable(AUDIBLE, UnavailableReason.NO_PROFILES_FOR_ROLE),
                        SourceStatus.Answered(HARDCOVER, 1),
                    )
                result.steps shouldBe listOf(PersonSearchStep.ViaYourBooks(2), PersonSearchStep.ByName("Ray Porter"))
                result.inLibrary shouldBe InLibrary(2, listOf("Project Hail Mary", "Heaven's River"))
                result.candidates.single().foundIn shouldBe listOf(HARDCOVER)
            }
        }

        test("an author search asks both, each with its own refs; Audnexus hits are Audible refs") {
            runTest {
                val rig = PeopleRig()
                rig.audnexus.answers(listOf(person("B001", "Andy Weir", setOf(ContributorRole.AUTHOR), setOf("phm"))))
                rig.hardcover.answers(listOf(person("123645", "Andy Weir", setOf(ContributorRole.AUTHOR), setOf("phm"))))
                val subject =
                    porterSubject(
                        role = ContributorRole.AUTHOR,
                        refs = listOf(ExternalRef("audible", "B001"), ExternalRef("hardcover", "123645")),
                    ).copy(name = "Andy Weir")

                val result = rig.finder.find(subject, PersonFindRequest(ContributorRole.AUTHOR, query = "A. Weir"), US)

                rig.audnexus.asked
                    .single()
                    .keys shouldBe listOf("B001")
                rig.hardcover.asked
                    .single()
                    .keys shouldBe listOf("123645")
                rig.hardcover.asked
                    .single()
                    .name shouldBe "A. Weir"
                result.candidates
                    .single()
                    .key.refs shouldBe
                    listOf(ExternalRef("audible", "B001"), ExternalRef("hardcover", "123645"))
                result.steps.last() shouldBe PersonSearchStep.ByName("A. Weir")
            }
        }

        test("a slow source times out, a throttled one says when, and the others still answer") {
            runTest {
                val rig = PeopleRig()
                rig.hardcover.latency = 30.seconds
                rig.audnexus.answer = { AppResult.Failure(MetadataError.ExternalRateLimited(retryAfterSeconds = 9)) }

                val result = rig.finder.find(porterSubject(role = ContributorRole.AUTHOR), PersonFindRequest(ContributorRole.AUTHOR), US)

                result.sources shouldBe listOf(SourceStatus.RateLimited(AUDIBLE, 9), SourceStatus.TimedOut(HARDCOVER))
                result.candidates shouldBe emptyList()
            }
        }

        test("no profile anywhere: no candidates, and coverage says why") {
            runTest {
                val rig = PeopleRig()
                rig.hardcover.availability = FindAvailability.Unavailable(UnavailableReason.NOT_CONFIGURED)

                val result = rig.finder.find(porterSubject(), PersonFindRequest(ContributorRole.NARRATOR), US)

                result.candidates shouldBe emptyList()
                result.sources shouldBe
                    listOf(
                        SourceStatus.Unavailable(AUDIBLE, UnavailableReason.NO_PROFILES_FOR_ROLE),
                        SourceStatus.Unavailable(HARDCOVER, UnavailableReason.NOT_CONFIGURED),
                    )
                rig.hardcover.asked shouldBe emptyList()
            }
        }

        test("a retry serves answered sources from the cache") {
            runTest {
                val rig = PeopleRig()
                rig.hardcover.answers(listOf(person("250716")))
                repeat(2) { rig.finder.find(porterSubject(), PersonFindRequest(ContributorRole.NARRATOR), US) }
                rig.hardcover.asked.size shouldBe 1
            }
        }
    })
