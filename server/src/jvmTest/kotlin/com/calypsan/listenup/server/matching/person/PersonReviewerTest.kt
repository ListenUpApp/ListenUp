package com.calypsan.listenup.server.matching.person

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.FieldValue
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.ContributorField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.server.matching.review.AUDNEXUS
import com.calypsan.listenup.server.matching.review.HARDCOVER
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.testing.shouldSucceed
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

private val US = MetadataLocale("us")

private class Rig {
    val audnexus = FakeProfileSource(AUDNEXUS)
    val hardcover = FakeProfileSource(HARDCOVER)
    val reviewer =
        PersonReviewer(
            coordinator =
                EnrichmentCoordinator(MetadataProviderRegistry(listOf(audnexus, hardcover)), EnrichmentRoutes.DEFAULT),
            displayName = { if (it == "u1") "Sam" else null },
        )

    suspend fun review(
        you: ContributorSyncPayload = yourRay(),
        key: PersonCandidateKey = PERSON_KEY,
    ) = reviewer.review(you, key, ContributorRole.NARRATOR, US)
}

private fun AppResult<*>.error() = (this as AppResult.Failure).error

private fun userEdit(field: ContributorField) = mapOf(field to FieldProvenance(FieldSourceKind.USER, provider = null, at = 12L, by = "u1"))

/** Person Review: photo and biography, each Yours → Proposed per source, with book Review's state rules. */
class PersonReviewerTest :
    FunSpec({
        test("each source is asked for its own ref, and only for it") {
            runTest {
                val rig = Rig()
                rig.audnexus.profiles = mapOf("B0RAY" to ray("B0RAY", bio = "A bio."))
                rig.review().shouldSucceed()
                rig.audnexus.asked shouldContainExactly listOf("B0RAY")
                rig.hardcover.asked shouldContainExactly listOf("250716")
            }
        }

        test("a source not in the candidate's key is never asked") {
            runTest {
                val rig = Rig()
                rig.hardcover.profiles = mapOf("250716" to ray("250716", photo = HARDCOVER_PHOTO))
                rig.review(key = PersonCandidateKey(listOf(ExternalRef("hardcover", "250716")))).shouldSucceed()
                rig.audnexus.asked shouldBe emptyList()
            }
        }

        test("an empty biography is filled from the route's first source, every bio an option, attributed") {
            runTest {
                val rig = Rig()
                rig.audnexus.profiles = mapOf("B0RAY" to ray("B0RAY", bio = "Audible bio."))
                rig.hardcover.profiles = mapOf("250716" to ray("250716", bio = "Hardcover bio."))
                val bio =
                    rig
                        .review()
                        .shouldSucceed()
                        .review.biography
                        .shouldNotBeNull()
                bio.state shouldBe FieldState.FILLS_GAP
                bio.options.map { (it.value as FieldValue.Text).text } shouldBe listOf("Audible bio.", "Hardcover bio.")
                bio.options.map { o -> o.sources.map { it.id } } shouldBe listOf(listOf("audible"), listOf("hardcover"))
                bio.defaultChoice shouldBe FieldChoice.Option(bio.options.first().optionId)
                bio.current.shouldBeNull()
            }
        }

        test("identical bios from two sources are one option listing both") {
            runTest {
                val rig = Rig()
                rig.audnexus.profiles = mapOf("B0RAY" to ray("B0RAY", bio = "<p>Same  bio.</p>"))
                rig.hardcover.profiles = mapOf("250716" to ray("250716", bio = "same bio."))
                val bio =
                    rig
                        .review()
                        .shouldSucceed()
                        .review.biography
                        .shouldNotBeNull()
                bio.options.size shouldBe 1
                bio.options
                    .single()
                    .sources
                    .map { it.id } shouldBe listOf("audible", "hardcover")
            }
        }

        test("a bio that equals yours is the same and kept; one that differs changes") {
            runTest {
                val rig = Rig()
                rig.audnexus.profiles = mapOf("B0RAY" to ray("B0RAY", bio = "Mine."))
                val same =
                    rig
                        .review(yourRay(description = " mine. "))
                        .shouldSucceed()
                        .review.biography!!
                same.state shouldBe FieldState.SAME
                same.defaultChoice shouldBe FieldChoice.KeepCurrent
                val changes =
                    rig
                        .review(yourRay(description = "Old."))
                        .shouldSucceed()
                        .review.biography!!
                changes.state shouldBe FieldState.CHANGES
                changes.current shouldBe "Old."
                changes.defaultChoice shouldBe FieldChoice.Option(changes.options.first().optionId)
            }
        }

        test("a hand-edited bio is protected, unticked and names its editor") {
            runTest {
                val rig = Rig()
                rig.audnexus.profiles = mapOf("B0RAY" to ray("B0RAY", bio = "Theirs."))
                val you = yourRay(description = "Mine.").copy(fieldProvenance = userEdit(ContributorField.BIOGRAPHY))
                val bio =
                    rig
                        .review(you)
                        .shouldSucceed()
                        .review.biography!!
                bio.state shouldBe FieldState.USER_EDITED
                bio.defaultChoice shouldBe FieldChoice.KeepCurrent
                bio.handEdit shouldBe HandEdit(byUserId = "u1", byName = "Sam", at = 12L)
            }
        }

        test("no source with a bio leaves the biography out") {
            runTest {
                val rig = Rig()
                rig.hardcover.profiles = mapOf("250716" to ray("250716", photo = HARDCOVER_PHOTO))
                rig
                    .review()
                    .shouldSucceed()
                    .review.biography
                    .shouldBeNull()
            }
        }

        test("photo tiles come in route order, and the first is the default for a photo not set by hand") {
            runTest {
                val rig = Rig()
                rig.audnexus.profiles = mapOf("B0RAY" to ray("B0RAY", photo = AUDIBLE_PHOTO))
                rig.hardcover.profiles = mapOf("250716" to ray("250716", photo = HARDCOVER_PHOTO))
                val photo =
                    rig
                        .review(yourRay(imagePath = "contributors/old.jpg"))
                        .shouldSucceed()
                        .review.photo
                photo.options.map { it.url } shouldBe listOf(AUDIBLE_PHOTO, HARDCOVER_PHOTO)
                photo.options.map { it.source.id } shouldBe listOf("audible", "hardcover")
                photo.current shouldBe "contributors/old.jpg"
                photo.setByHand shouldBe false
                photo.defaultChoice shouldBe ImageChoice.Candidate(photo.options.first().optionId)
            }
        }

        test("a photo set by hand is kept by default (decision 6)") {
            runTest {
                val rig = Rig()
                rig.hardcover.profiles = mapOf("250716" to ray("250716", photo = HARDCOVER_PHOTO))
                val you = yourRay(imagePath = "contributors/mine.jpg").copy(fieldProvenance = userEdit(ContributorField.PHOTO))
                val photo =
                    rig
                        .review(you)
                        .shouldSucceed()
                        .review.photo
                photo.setByHand shouldBe true
                photo.defaultChoice shouldBe ImageChoice.KeepCurrent
            }
        }

        test("option ids are stable across derivations") {
            runTest {
                val rig = Rig()
                rig.audnexus.profiles = mapOf("B0RAY" to ray("B0RAY", bio = "Bio.", photo = AUDIBLE_PHOTO))
                val first = rig.review().shouldSucceed().review
                val second = rig.review().shouldSucceed().review
                second.photo.options shouldBe first.photo.options
                second.biography shouldBe first.biography
            }
        }

        test("the review is based on the person's revision and never offers the name") {
            runTest {
                val rig = Rig()
                rig.audnexus.profiles = mapOf("B0RAY" to ray("B0RAY", bio = "Bio."))
                val review = rig.review().shouldSucceed().review
                review.basedOnRevision shouldBe 9
                review.role shouldBe ContributorRole.NARRATOR
                review.candidate shouldBe PERSON_KEY
            }
        }

        test("an empty regional shell — profiles with no bio and no photo — is not found") {
            runTest {
                val rig = Rig()
                rig.audnexus.profiles = mapOf("B0RAY" to ray("B0RAY"))
                rig.review().error().shouldBeInstanceOf<MetadataError.NotFound>()
            }
        }

        test("no profile anywhere is not found") {
            runTest { Rig().review().error().shouldBeInstanceOf<MetadataError.NotFound>() }
        }

        test("a source that doesn't answer in time is a timeout") {
            runTest {
                val rig = Rig()
                rig.audnexus.slow = 30.seconds
                rig
                    .review(key = PersonCandidateKey(listOf(ExternalRef("audible", "B0RAY"))))
                    .error()
                    .shouldBeInstanceOf<MetadataError.ExternalTimeout>()
            }
        }

        test("a rate-limited source says when to retry; every source failing is unavailable") {
            runTest {
                val rig = Rig()
                rig.audnexus.profiles =
                    mapOf("B0RAY" to AppResult.Failure(MetadataError.ExternalRateLimited(retryAfterSeconds = 30)))
                val limited = rig.review().error().shouldBeInstanceOf<MetadataError.ExternalRateLimited>()
                limited.retryAfterSeconds shouldBe 30
                rig.audnexus.profiles = mapOf("B0RAY" to AppResult.Failure(MetadataError.ExternalUnavailable()))
                rig.hardcover.profiles = mapOf("250716" to AppResult.Failure(MetadataError.ExternalUnavailable()))
                rig.review().error().shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
            }
        }
    })
