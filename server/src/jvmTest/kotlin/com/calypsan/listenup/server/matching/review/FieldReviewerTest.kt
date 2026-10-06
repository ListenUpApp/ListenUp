package com.calypsan.listenup.server.matching.review

import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.FieldValue
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.metadata.ComposedOptions
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private fun review(
    book: BookSyncPayload,
    options: ComposedOptions,
) = FieldReviewer.review(book, options, EnrichmentRoutes.DEFAULT) { HandEdit(it.by, null, it.at.takeIf { at -> at > 0 }) }

private fun List<ReviewedField>.of(field: BookField) = firstOrNull { it.review.field == field }

private fun BookSyncPayload.handEdited(field: BookField) =
    copy(fieldProvenance = fieldProvenance + (field to FieldProvenance(FieldSourceKind.USER, at = 5L, by = "u1")))

class FieldReviewerTest :
    FunSpec({
        test("an empty field that a source has fills a gap, ticked with the route's first option") {
            val fields = review(yourBook(description = null), options(AUDIBLE to core(description = "A story.")))
            val description = fields.of(BookField.DESCRIPTION).shouldNotBeNull().review
            description.state shouldBe FieldState.FILLS_GAP
            description.current.shouldBeNull()
            description.defaultChoice shouldBe FieldChoice.Option(description.options.single().optionId)
        }

        test("a value equal to the best option is the same, kept, even when hand-edited") {
            val book = yourBook(title = "Project  hail mary ").handEdited(BookField.TITLE)
            val title = review(book, options(AUDIBLE to core(title = "Project Hail Mary"))).of(BookField.TITLE)!!
            title.review.state shouldBe FieldState.SAME
            title.review.defaultChoice shouldBe FieldChoice.KeepCurrent
        }

        test("a different value you didn't edit changes, ticked") {
            val title = review(yourBook(), options(AUDIBLE to core(title = "Project Hail Mary: A Novel"))).of(BookField.TITLE)!!
            title.review.state shouldBe FieldState.CHANGES
            title.review.defaultChoice shouldBe FieldChoice.Option(title.review.options.first().optionId)
        }

        test("a different value you edited by hand is protected: unticked, flagged with who and when") {
            val book = yourBook().handEdited(BookField.TITLE)
            val title = review(book, options(AUDIBLE to core(title = "Other"))).of(BookField.TITLE)!!
            title.review.state shouldBe FieldState.USER_EDITED
            title.review.defaultChoice shouldBe FieldChoice.KeepCurrent
            title.review.handEdit shouldBe HandEdit("u1", null, 5L)
        }

        test("a pre-tracking hand edit is still an edit, with its unknowns left null") {
            val book =
                yourBook().copy(
                    fieldProvenance = mapOf(BookField.TITLE to FieldProvenance(FieldSourceKind.USER)),
                )
            val title = review(book, options(AUDIBLE to core(title = "Other"))).of(BookField.TITLE)!!
            title.review.state shouldBe FieldState.USER_EDITED
            title.review.handEdit shouldBe HandEdit(null, null, null)
        }

        test("a field no provider has is not reviewed at all") {
            review(yourBook(), options(AUDIBLE to core(title = "Project Hail Mary"))).of(BookField.PUBLISHER).shouldBeNull()
        }

        test("identical values from two providers are one option listing both sources, Audnexus shown as Audible") {
            val fields =
                review(
                    yourBook(),
                    options(
                        AUDIBLE to core(publisher = "Audible Studios"),
                        HARDCOVER to core(publisher = "audible  studios"),
                        AUDNEXUS to core(publisher = "Random House"),
                    ),
                )
            val publisher = fields.of(BookField.PUBLISHER)!!
            publisher.review.options.size shouldBe 2
            publisher.review.options[0].sources.map { it.label } shouldBe listOf("Audible", "Hardcover")
            publisher.review.options[1].sources.map { it.label } shouldBe listOf("Audible")
        }

        test("option ids are stable across derivations and differ between values") {
            val first = review(yourBook(), options(AUDIBLE to core(description = "<p>A  story.</p>")))
            val second = review(yourBook(), options(AUDIBLE to core(description = "<p>A  story.</p>")))
            val id = first.of(BookField.DESCRIPTION)!!.review.options.single().optionId
            id shouldBe second.of(BookField.DESCRIPTION)!!.review.options.single().optionId
            id.startsWith("audible:") shouldBe true
            id shouldNotBe review(yourBook(), options(AUDIBLE to core(description = "Another."))).of(BookField.DESCRIPTION)!!
                .review.options.single().optionId
        }

        test("descriptions compare without their HTML") {
            val book = yourBook(description = "A story.")
            review(book, options(AUDIBLE to core(description = "<p>A <b>story</b>.</p>")))
                .of(BookField.DESCRIPTION)!!.review.state shouldBe FieldState.SAME
        }

        test("people compare as an order-insensitive name set") {
            val book = yourBook(authors = listOf("Andy Weir", "Ray Porter"))
            review(book, options(AUDIBLE to core(authors = listOf("ray porter", "Andy Weir"))))
                .of(BookField.AUTHORS)!!.review.state shouldBe FieldState.SAME
        }

        test("release dates compare at the year, and the option keeps the provider's full date") {
            val same = review(yourBook(publishYear = 2021), options(AUDIBLE to core(releaseDate = "2021-05-04")))
            same.of(BookField.PUBLISH_YEAR)!!.review.state shouldBe FieldState.SAME
            val changes = review(yourBook(publishYear = 2020), options(AUDIBLE to core(releaseDate = "2021-05-04")))
            val year = changes.of(BookField.PUBLISH_YEAR)!!
            year.review.state shouldBe FieldState.CHANGES
            year.review.options.single().value shouldBe FieldValue.Year(2021)
            year.options.single().write shouldBe OptionWrite.Year(2021, "2021-05-04")
        }

        test("a year from one source and the full date from another collapse, keeping the full date") {
            val fields =
                review(
                    yourBook(publishYear = null),
                    options(AUDIBLE to core(releaseDate = "2021"), HARDCOVER to core(releaseDate = "2021-05-04")),
                )
            fields.of(BookField.PUBLISH_YEAR)!!.options.single().write shouldBe OptionWrite.Year(2021, "2021-05-04")
        }

        test("series compare by name and sequence") {
            val book =
                yourBook().copy(
                    series = listOf(com.calypsan.listenup.api.sync.BookSeriesPayload("s1", "Bobiverse", 1.0)),
                )
            val opts = { seq: String -> ComposedOptions(emptyMap(), emptySet(), emptyMap(), series = mapOf(AUDIBLE to listOf(SeriesMeta(title = "bobiverse", sequence = seq)))) }
            review(book, opts("1")).of(BookField.SERIES)!!.review.state shouldBe FieldState.SAME
            review(book, opts("2")).of(BookField.SERIES)!!.review.state shouldBe FieldState.CHANGES
        }

        test("a provider that supplies nothing reviewable yields no fields") {
            review(yourBook(), options(AUDIBLE to core())).shouldBeEmpty()
        }
    })
