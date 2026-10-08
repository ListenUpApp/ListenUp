package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.InLibrary
import com.calypsan.listenup.api.dto.match.MatchTier
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
    val audnexus = FakePeopleSource(MetadataProviderId.AUDNEXUS)
    val hardcover = FakePeopleSource(MetadataProviderId.HARDCOVER)
    val finder = PeopleFinder(MetadataProviderRegistry(listOf(hardcover, audnexus)), EnrichmentRoutes.DEFAULT)
}

/** People Find's fan-out: every source asked whatever the role, deadlines, typed statuses, steps, the no-profile path. */
class PeopleFinderTest :
    FunSpec({
        test("a narrator is looked for at every source — Audible's author pages too — and merged across them") {
            runTest {
                val rig = PeopleRig()
                rig.hardcover.answers(listOf(person("250716", credited = setOf("phm"))), setOf(PersonStep.NAME, PersonStep.VIA_BOOKS))
                rig.audnexus.answers(listOf(person("B0PORTER", roles = setOf(ContributorRole.AUTHOR), credited = setOf("phm"))))

                val result = rig.finder.find(porterSubject(), PersonFindRequest(), US)

                rig.audnexus.asked.single().name shouldBe "Ray Porter"
                rig.hardcover.asked.single().name shouldBe "Ray Porter"
                result.role shouldBe null
                result.coverage shouldBe listOf(RoleCoverage(AUDIBLE, true), RoleCoverage(HARDCOVER, true))
                result.sources shouldBe listOf(SourceStatus.Answered(AUDIBLE, 1), SourceStatus.Answered(HARDCOVER, 1))
                result.steps shouldBe listOf(PersonSearchStep.ViaYourBooks(2), PersonSearchStep.ByName("Ray Porter"))
                result.inLibrary shouldBe InLibrary(2, listOf("Project Hail Mary", "Heaven's River"))
                val porter = result.candidates.single()
                porter.foundIn shouldBe listOf(AUDIBLE, HARDCOVER)
                porter.roles shouldBe listOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR)
            }
        }

        test("an older client's role changes nothing but the echo") {
            runTest {
                val rig = PeopleRig()
                rig.hardcover.answers(listOf(person("250716", credited = setOf("phm"))))

                val result = rig.finder.find(porterSubject(), PersonFindRequest(role = ContributorRole.AUTHOR), US)

                result.role shouldBe ContributorRole.AUTHOR
                rig.audnexus.asked.size shouldBe 1
                result.candidates.single().tier shouldBe MatchTier.STRONG
            }
        }

        test("an author search asks both, each with its own refs; Audnexus hits are Audible refs") {
            runTest {
                val rig = PeopleRig()
                rig.audnexus.answers(listOf(person("B001", "Andy Weir", setOf(ContributorRole.AUTHOR), setOf("phm"))))
                rig.hardcover.answers(listOf(person("123645", "Andy Weir", setOf(ContributorRole.AUTHOR), setOf("phm"))))
                val subject =
                    porterSubject(
                        refs = listOf(ExternalRef("audible", "B001"), ExternalRef("hardcover", "123645")),
                    ).copy(name = "Andy Weir")

                val result = rig.finder.find(subject, PersonFindRequest(query = "A. Weir"), US)

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

                val result = rig.finder.find(porterSubject(), PersonFindRequest(), US)

                result.sources shouldBe listOf(SourceStatus.RateLimited(AUDIBLE, 9), SourceStatus.TimedOut(HARDCOVER))
                result.candidates shouldBe emptyList()
            }
        }

        test("no profile anywhere: no candidates, and each source says why") {
            runTest {
                val rig = PeopleRig()
                rig.hardcover.availability = FindAvailability.Unavailable(UnavailableReason.NOT_CONFIGURED)

                val result = rig.finder.find(porterSubject(), PersonFindRequest(), US)

                result.candidates shouldBe emptyList()
                result.sources shouldBe
                    listOf(
                        SourceStatus.Answered(AUDIBLE, 0),
                        SourceStatus.Unavailable(HARDCOVER, UnavailableReason.NOT_CONFIGURED),
                    )
                rig.hardcover.asked shouldBe emptyList()
            }
        }

        test("a retry serves answered sources from the cache") {
            runTest {
                val rig = PeopleRig()
                rig.hardcover.answers(listOf(person("250716")))
                repeat(2) { rig.finder.find(porterSubject(), PersonFindRequest(), US) }
                rig.hardcover.asked.size shouldBe 1
            }
        }
    })
