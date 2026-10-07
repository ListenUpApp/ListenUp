package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.ContributorRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val AUDIBLE = MetadataSource(id = "audible", label = "Audible")
private val HARDCOVER = MetadataSource(id = "hardcover", label = "Hardcover")
private val KEY = PersonCandidateKey(listOf(ExternalRef("audible", "B001"), ExternalRef("hardcover", "250716")))

private val REVIEW =
    PersonMatchReview(
        candidate = KEY,
        role = ContributorRole.NARRATOR,
        basedOnRevision = 42,
        photo =
            PhotoReview(
                current = "contributors/abc.jpg",
                setByHand = true,
                options = listOf(PhotoCandidate("hardcover:1234", HARDCOVER, "https://example.test/p.jpg")),
                defaultChoice = ImageChoice.KeepCurrent,
            ),
        biography =
            BiographyReview(
                current = "Old bio.",
                options = listOf(FieldOption("audible:5678", FieldValue.Text("New bio."), listOf(AUDIBLE))),
                defaultChoice = FieldChoice.KeepCurrent,
                state = FieldState.USER_EDITED,
                handEdit = HandEdit(byUserId = "u1", byName = "Sam", at = 12L),
            ),
    )

/** Every person Review and Apply type crosses the wire intact. */
class PersonReviewContractTest :
    FunSpec({
        test("a person Review round-trips") {
            val json = contractJson.encodeToString(PersonMatchReview.serializer(), REVIEW)
            contractJson.decodeFromString(PersonMatchReview.serializer(), json) shouldBe REVIEW
        }

        test("a person Review with no biography from any source round-trips") {
            val review = REVIEW.copy(biography = null)
            contractJson.decodeFromString(
                PersonMatchReview.serializer(),
                contractJson.encodeToString(PersonMatchReview.serializer(), review),
            ) shouldBe review
        }

        test("a person Apply round-trips, with photo and biography chosen separately") {
            val apply =
                PersonMatchApply(
                    candidate = KEY,
                    role = ContributorRole.AUTHOR,
                    basedOnRevision = 42,
                    photo = ImageChoice.Candidate("hardcover:1234"),
                    biography = FieldChoice.KeepCurrent,
                )
            contractJson.decodeFromString(
                PersonMatchApply.serializer(),
                contractJson.encodeToString(PersonMatchApply.serializer(), apply),
            ) shouldBe apply
        }
    })
