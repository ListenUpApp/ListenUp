package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.PhotoCandidate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * [PersonReviewChoices] — photo and biography are two decisions. Ticking or switching one must never move the
 * other, a hand-edited biography starts kept, and a hand-set photo defaults to Keep current.
 */
class PersonReviewChoicesTest :
    FunSpec({
        val candidate = choosePersonFindOutcome(personFindResult()).let { (it as PersonFindOutcome.Candidates).strong.single() }

        fun PersonReviewChoices.ready(review: com.calypsan.listenup.api.dto.match.PersonMatchReview = personReview()) =
            project(candidate, review, applying = false, applyError = null)

        test("the server's defaults: a missing photo and a missing biography both fill a gap, ticked") {
            val ready = PersonReviewChoices().ready()
            val photo = ready.photo.shouldNotBeNull()
            photo.state shouldBe FieldState.FILLS_GAP
            photo.isTicked shouldBe true
            photo.chosen?.source shouldBe HARDCOVER
            val bio = ready.biography.shouldNotBeNull()
            bio.state shouldBe FieldState.FILLS_GAP
            bio.isTicked shouldBe true
            ready.applyBar shouldBe PersonApplySummary(photo = true, biography = true, sources = listOf(HARDCOVER))
        }

        test("unticking the photo leaves the biography ticked, and the request carries each choice separately") {
            val choices = PersonReviewChoices().setPhotoTicked(personReview(), ticked = false)
            val ready = choices.ready()
            ready.photo?.isTicked shouldBe false
            ready.biography?.isTicked shouldBe true
            ready.applyBar shouldBe PersonApplySummary(photo = false, biography = true, sources = listOf(HARDCOVER))
            val request = choices.toApply(personReview())
            request.photo shouldBe ImageChoice.KeepCurrent
            request.biography shouldBe FieldChoice.Option("b-hc")
            request.basedOnRevision shouldBe 7L
            request.role shouldBe null
        }

        test("unticking the biography leaves the photo ticked") {
            val choices = PersonReviewChoices().setBiographyTicked(personReview(), ticked = false)
            val request = choices.toApply(personReview())
            request.photo shouldBe ImageChoice.Candidate("p-hc")
            request.biography shouldBe FieldChoice.KeepCurrent
        }

        test("a hand-edited biography starts unticked and flagged; ticking it takes the route's first source") {
            val review = personReview(biography = editedBiography())
            val bio = PersonReviewChoices().ready(review).biography.shouldNotBeNull()
            bio.state shouldBe FieldState.USER_EDITED
            bio.isTicked shouldBe false
            bio.handEdit.shouldNotBeNull()
            bio.canKeepYours shouldBe true
            val ticked = PersonReviewChoices().setBiographyTicked(review, ticked = true)
            ticked.toApply(review).biography shouldBe FieldChoice.Option("b-au")
        }

        test("switching the biography source and unticking then re-ticking restores that source") {
            val review = personReview(biography = editedBiography())
            val choices =
                PersonReviewChoices()
                    .chooseBiography(FieldChoice.Option("b-hc"))
                    .setBiographyTicked(review, ticked = false)
                    .setBiographyTicked(review, ticked = true)
            choices.toApply(review).biography shouldBe FieldChoice.Option("b-hc")
            choices
                .ready(review)
                .biography
                ?.proposed
                ?.optionId shouldBe "b-hc"
        }

        test("a hand-set photo is flagged you-edited and defaults to Keep current") {
            val review = personReview(currentPhoto = "contributors/p.jpg", photoSetByHand = true, photoDefault = ImageChoice.KeepCurrent)
            val photo = PersonReviewChoices().ready(review).photo.shouldNotBeNull()
            photo.state shouldBe FieldState.USER_EDITED
            photo.isTicked shouldBe false
            photo.proposed.optionId shouldBe "p-hc"
            PersonReviewChoices().toApply(review).photo shouldBe ImageChoice.KeepCurrent
        }

        test("a photo that replaces one from a source is a change") {
            val review = personReview(currentPhoto = "contributors/old.jpg")
            PersonReviewChoices().ready(review).photo?.state shouldBe FieldState.CHANGES
        }

        test("choosing another source's photo, then a reload that drops it, falls back to the server's default") {
            val two =
                personReview(
                    photoOptions =
                        listOf(
                            PhotoCandidate("p-hc", HARDCOVER, "https://img/hc.jpg"),
                            PhotoCandidate("p-au", AUDIBLE, "https://img/au.jpg"),
                        ),
                )
            val choices = PersonReviewChoices().choosePhoto(ImageChoice.Candidate("p-au"))
            choices.toApply(two).photo shouldBe ImageChoice.Candidate("p-au")
            choices.toApply(personReview()).photo shouldBe ImageChoice.Candidate("p-hc")
        }

        test("no photo options and no biography: nothing to review, nothing to apply") {
            val review = personReview(photoOptions = emptyList(), photoDefault = ImageChoice.KeepCurrent, biography = null)
            val ready = PersonReviewChoices().ready(review)
            ready.photo.shouldBeNull()
            ready.biography.shouldBeNull()
            ready.applyBar.canApply shouldBe false
        }

        test("a biography already the same is shown as such and never ticked") {
            val review =
                personReview(
                    biography =
                        editedBiography().copy(
                            current = "Same.",
                            state = FieldState.SAME,
                            handEdit = null,
                        ),
                )
            val bio = PersonReviewChoices().ready(review).biography.shouldNotBeNull()
            bio.state shouldBe FieldState.SAME
            bio.isTicked shouldBe false
        }
    })
