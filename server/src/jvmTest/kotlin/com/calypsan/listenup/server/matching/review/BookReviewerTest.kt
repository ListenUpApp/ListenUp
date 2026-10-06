package com.calypsan.listenup.server.matching.review

import com.calypsan.listenup.api.dto.match.BookCandidateKey
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.testing.shouldSucceed
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

private val US = MetadataLocale("us")
private val KEY = BookCandidateKey(listOf(ExternalRef("audible", "B0X", "uk"), ExternalRef("hardcover", "77")))

private fun reviewer(vararg providers: FakeCatalogProvider) =
    BookReviewer(
        coordinator = EnrichmentCoordinator(MetadataProviderRegistry(providers.toList()), EnrichmentRoutes.DEFAULT),
        probe = { null },
        genreIdentity = { _ -> LabelIdentity { s, yours -> yours.any { it.equals(s, ignoreCase = true) } } },
        currentMoods = { listOf(BookMood("m1", "Hopeful")) },
        displayName = { if (it == "u1") "Sam" else null },
    )

class BookReviewerTest :
    FunSpec({
        test("each provider is asked by the candidate's own refs, and the review carries the book's revision") {
            runTest {
                val audible = FakeCatalogProvider(AUDIBLE, core = AppResult.Success(core(description = "New.")))
                val hardcover = FakeCatalogProvider(HARDCOVER, moods = listOf("Hopeful", "Tense"))
                val model = reviewer(audible, hardcover).review(yourBook(), KEY, US).shouldSucceed()
                audible.asked.first().asin shouldBe "B0X"
                hardcover.asked.first().refFor(HARDCOVER)?.id shouldBe "77"
                model.review.basedOnRevision shouldBe 7L
                model.review.moods.yours shouldBe listOf("Hopeful")
                model.review.moods.suggested.map { it.label } shouldBe listOf("Tense")
                model.review.fields.single { it.field == BookField.DESCRIPTION }.state shouldBe FieldState.FILLS_GAP
            }
        }

        test("hand edits name their editor") {
            runTest {
                val book =
                    yourBook().copy(
                        fieldProvenance = mapOf(BookField.TITLE to FieldProvenance(FieldSourceKind.USER, at = 9L, by = "u1")),
                    )
                val audible = FakeCatalogProvider(AUDIBLE, core = AppResult.Success(core(title = "Other")))
                val title = reviewer(audible).review(book, KEY, US).shouldSucceed().review.fields.single { it.field == BookField.TITLE }
                title.handEdit?.byName shouldBe "Sam"
            }
        }

        test("a candidate none of whose sources answer is not found") {
            runTest {
                reviewer(FakeCatalogProvider(AUDIBLE)).review(yourBook(), KEY, US).let {
                    (it as AppResult.Failure).error.shouldBeInstanceOf<MetadataError.NotFound>()
                }
            }
        }

        test("a candidate whose sources all failed is unavailable") {
            runTest {
                val failing = FakeCatalogProvider(AUDIBLE, core = AppResult.Failure(MetadataError.ExternalUnavailable()))
                (reviewer(failing).review(yourBook(), KEY, US) as AppResult.Failure)
                    .error.shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
            }
        }

        test("a rate-limited source is ExternalRateLimited with its retry-after") {
            runTest {
                val limited = FakeCatalogProvider(AUDIBLE, core = AppResult.Failure(MetadataError.ExternalRateLimited(retryAfterSeconds = 12)))
                val error = (reviewer(limited).review(yourBook(), KEY, US) as AppResult.Failure).error
                (error as MetadataError.ExternalRateLimited).retryAfterSeconds shouldBe 12
            }
        }

        test("a source slower than the deadline is ExternalTimeout") {
            runTest {
                val slow = FakeCatalogProvider(AUDIBLE, core = AppResult.Success(core(title = "T")), slow = 30.seconds)
                (reviewer(slow).review(yourBook(), KEY, US) as AppResult.Failure)
                    .error.shouldBeInstanceOf<MetadataError.ExternalTimeout>()
            }
        }

        test("a Hardcover-only candidate is reviewable") {
            runTest {
                val hardcover = FakeCatalogProvider(HARDCOVER, core = AppResult.Success(core(description = "From Hardcover.")))
                val key = BookCandidateKey(listOf(ExternalRef("hardcover", "77")))
                reviewer(FakeCatalogProvider(AUDIBLE), hardcover).review(yourBook(), key, US).shouldSucceed()
                    .review.fields.single().options.single().sources.single().label shouldBe "Hardcover"
            }
        }
    })
