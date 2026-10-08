package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldDecision
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.LabelSetChange
import com.calypsan.listenup.api.metadata.BookField
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The Review's choice rules (Simon's decisions 1, 2, 3 and 6): only fields that change are ticked, hand edits start
 * unticked, unticking keeps yours and re-ticking restores the last source, and one Apply carries every decision.
 */
class ReviewChoicesTest :
    FunSpec({
        val candidate = findResult().candidates.first().toUi()

        fun ready(choices: ReviewChoices = ReviewChoices()) =
            choices.project(candidate, review(), currentCoverPath = "/covers/phm.jpg", applying = false, applyError = null)

        test("defaults tick changes and gaps, leave the hand edit unticked, and collapse what's already the same") {
            val state = ready()
            state.changes.map { it.field to it.isTicked } shouldBe listOf(BookField.DESCRIPTION to true)
            state.fillsGap.map { it.field to it.isTicked } shouldBe listOf(BookField.PUBLISHER to true, BookField.PUBLISH_YEAR to true)
            state.youEdited.map { it.field to it.isTicked } shouldBe listOf(BookField.TITLE to false)
            state.alreadySame shouldBe listOf(BookField.SUBTITLE)
            state.lengthAlreadySame shouldBe true
        }

        test("the hand-edited field still shows what would be proposed, and its edit") {
            val title = ready().youEdited.single()
            title.choice shouldBe FieldChoice.KeepCurrent
            title.proposed.optionId shouldBe "audible:t"
            title.handEdit?.byName shouldBe "Simon"
        }

        test("the canvas counts: 5 fields (3 + genres + moods), the cover, 3 chapter names, 1 kept as edited") {
            val state = ready()
            state.applyBar shouldBe ApplySummary(fieldCount = 5, coverChanges = true, chapterNameCount = 3)
            state.summary shouldBe
                WhatWillChange(
                    coverSource = HARDCOVER,
                    changeCount = 1,
                    gapCount = 2,
                    labelsAdded = 3,
                    labelsRemoved = 0,
                    chapterNameCount = 3,
                    keptEditedCount = 1,
                )
        }

        test("unticking keeps yours; re-ticking restores the source chosen last, not the default") {
            val r = review()
            val description = r.fields.first { it.field == BookField.DESCRIPTION }
            val chosen = ReviewChoices().choose(BookField.DESCRIPTION, FieldChoice.Option("hardcover:d2"))
            val unticked = chosen.setTicked(description, ticked = false)
            unticked.choiceFor(description) shouldBe FieldChoice.KeepCurrent
            unticked.setTicked(description, ticked = true).choiceFor(description) shouldBe FieldChoice.Option("hardcover:d2")
        }

        test("ticking a hand edit selects the route's first option") {
            val title = review().fields.first { it.field == BookField.TITLE }
            ReviewChoices().setTicked(title, ticked = true).choiceFor(title) shouldBe FieldChoice.Option("audible:t")
        }

        test("a chosen option that no longer exists falls back to the server's default") {
            val choices = ReviewChoices().choose(BookField.DESCRIPTION, FieldChoice.Option("hardcover:d2"))
            val reloaded = review(descriptionOptions = listOf(option("audible:d9", text("New text"), AUDIBLE)))
            choices.choiceFor(reloaded.fields.first { it.field == BookField.DESCRIPTION }) shouldBe FieldChoice.Option("audible:d9")
        }

        test("removing a label of yours and deselecting a suggestion flow into the counts") {
            val choices =
                ReviewChoices()
                    .let { it.copy(removedYours = it.toggleLabel(it.removedYours, LabelKind.GENRES, "Space Opera", on = true)) }
                    .let { choices ->
                        choices.copy(
                            deselectedSuggestions =
                                choices.toggleLabel(
                                    choices.deselectedSuggestions,
                                    LabelKind.MOODS,
                                    "Hopeful",
                                    on = true,
                                ),
                        )
                    }
            val state = ready(choices)
            state.genres.removedLabels shouldBe listOf("Space Opera")
            state.moods.changes shouldBe false
            state.applyBar.fieldCount shouldBe 4
        }

        test("leaving chapter names out drops them from the bar") {
            ready(ReviewChoices(chapterNamesIncluded = false)).applyBar.chapterNameCount shouldBe 0
            ready(ReviewChoices(deselectedChapters = setOf(1))).applyBar.chapterNameCount shouldBe 2
        }

        test("keeping the current cover means the bar names no cover") {
            ready(ReviewChoices(cover = ImageChoice.KeepCurrent)).applyBar.coverChanges shouldBe false
        }

        test("one Apply carries every decision: fields, cover, both label sets and the chapter ordinals") {
            val r = review()
            val choices =
                ReviewChoices(deselectedChapters = setOf(0))
                    .let { it.copy(removedYours = it.toggleLabel(it.removedYours, LabelKind.GENRES, "Space Opera", on = true)) }
            val apply = choices.toApply(r)
            apply.candidate shouldBe BEST
            apply.basedOnRevision shouldBe 7L
            apply.fields shouldBe
                listOf(
                    FieldDecision(BookField.DESCRIPTION, FieldChoice.Option("audible:d1")),
                    FieldDecision(BookField.PUBLISHER, FieldChoice.Option("audible:p")),
                    FieldDecision(BookField.PUBLISH_YEAR, FieldChoice.Option("audible:y")),
                    FieldDecision(BookField.TITLE, FieldChoice.KeepCurrent),
                    FieldDecision(BookField.SUBTITLE, FieldChoice.KeepCurrent),
                )
            apply.cover shouldBe ImageChoice.Candidate("hardcover:c")
            apply.genres shouldBe LabelSetChange(add = listOf("Hard Science Fiction", "Thriller"), remove = listOf("Space Opera"))
            apply.moods shouldBe LabelSetChange(add = listOf("Hopeful"), remove = emptyList())
            apply.chapterOrdinals shouldBe listOf(1, 2)
        }
    })
