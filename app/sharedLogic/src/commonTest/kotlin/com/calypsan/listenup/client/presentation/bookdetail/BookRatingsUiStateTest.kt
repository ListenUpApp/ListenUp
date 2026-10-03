package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ScoreSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val AUDIBLE = ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.8, count = 11_000)
private val HARDCOVER = ExternalRating(source = ExternalRatingSource.HARDCOVER, average = 4.5, count = 1_200)
private val LISTENERS = ListenerAverage(averageHalfStars = 8.0, count = 3)

private val SCORE =
    CombinedScore(
        average = 4.6,
        count = 12_203,
        shares =
            mapOf(
                ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 0.62,
                ScoreSource.Outside(ExternalRatingSource.HARDCOVER) to 0.30,
                ScoreSource.Listeners to 0.08,
            ),
    )

private val LISTENERS_ONLY = CombinedScore(average = 4.4, count = 3, shares = mapOf(ScoreSource.Listeners to 1.0))

private fun ready(
    listeners: ListenerAverage? = null,
    external: CombinedScore? = null,
    breakdown: List<ExternalRating> = emptyList(),
    canRefresh: Boolean = false,
    isCheckingExternal: Boolean = false,
) = BookRatingsUiState.Ready(
    listeners = listeners,
    mine = null,
    external = external,
    breakdown = breakdown,
    canRefresh = canRefresh,
    isCheckingExternal = isCheckingExternal,
)

/** The "everyone" half's one decision, made once here so Android, iOS and web draw the same rows. */
class BookRatingsUiStateTest :
    FunSpec({
        test("a score an outside source joined is shown") {
            ready(listeners = LISTENERS, external = SCORE, breakdown = listOf(AUDIBLE, HARDCOVER)).scoreRow shouldBe
                ScoreRow.Shown(SCORE)
        }

        test("a score held up by the on-open check stays shown, and updates in place") {
            ready(external = SCORE, breakdown = listOf(AUDIBLE), isCheckingExternal = true).scoreRow shouldBe
                ScoreRow.Shown(SCORE)
        }

        test("with no score to show, the check holds the row") {
            ready(isCheckingExternal = true).scoreRow shouldBe ScoreRow.Checking
            ready(listeners = LISTENERS, external = LISTENERS_ONLY, isCheckingExternal = true).scoreRow shouldBe
                ScoreRow.Checking
        }

        test("nobody anywhere has rated it: No ratings yet") {
            ready().scoreRow shouldBe ScoreRow.NoRatings
        }

        test("only your listeners rated it: no second number") {
            ready(listeners = LISTENERS, external = LISTENERS_ONLY).scoreRow shouldBe ScoreRow.Absent
        }

        test("an admin refreshes inline only when there is no score to open the sources from") {
            ready(canRefresh = true).showsInlineRefresh shouldBe true
            ready(listeners = LISTENERS, external = LISTENERS_ONLY, canRefresh = true).showsInlineRefresh shouldBe true
            ready(external = SCORE, breakdown = listOf(AUDIBLE), canRefresh = true).showsInlineRefresh shouldBe false
            ready(canRefresh = true, isCheckingExternal = true).showsInlineRefresh shouldBe false
            ready(canRefresh = false).showsInlineRefresh shouldBe false
        }

        test("the score names its sources in the breakdown's order, then your listeners") {
            val state = ready(listeners = LISTENERS, external = SCORE, breakdown = listOf(AUDIBLE, HARDCOVER))

            state.outsideRatingsInScore.map { it.source } shouldBe
                listOf(ExternalRatingSource.AUDIBLE, ExternalRatingSource.HARDCOVER)
            state.listenersInScore shouldBe true
        }

        test("a source with a row but no share in the score is not named") {
            val audibleOnly = SCORE.copy(shares = mapOf(ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 1.0))
            val state = ready(external = audibleOnly, breakdown = listOf(AUDIBLE, HARDCOVER))

            state.outsideRatingsInScore shouldBe listOf(AUDIBLE)
            state.listenersInScore shouldBe false
        }
    })
