package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.model.ScoreSource
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.client.testing.Windows
import io.kotest.matchers.shouldBe
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

private fun mine(
    halfStars: Int = 9,
    note: String? = null,
    fromHardcover: Boolean = false,
) = ListenerRating(bookId = "b1", userId = "me", halfStars = halfStars, note = note, ratedAtMs = 1L, fromHardcover = fromHardcover)

private fun ready(
    mine: ListenerRating? = null,
    listeners: ListenerAverage? = null,
    external: CombinedScore? = null,
    breakdown: List<ExternalRating> = emptyList(),
    canRefresh: Boolean = false,
    isRefreshingExternal: Boolean = false,
    isCheckingExternal: Boolean = false,
) = BookRatingsUiState.Ready(
    listeners = listeners,
    mine = mine,
    external = external,
    breakdown = breakdown,
    canRefresh = canRefresh,
    isRefreshingExternal = isRefreshingExternal,
    isCheckingExternal = isCheckingExternal,
)

/** Book Detail's ratings, yours first: the stars you set, then everyone else in labelled rows. */
@RunWith(RobolectricTestRunner::class)
class BookRatingSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(
        state: BookRatingsUiState,
        onSetStars: (Int) -> Unit = {},
        onEditNote: () -> Unit = {},
        onRemove: () -> Unit = {},
        onOpenSources: () -> Unit = {},
        onRefreshExternal: () -> Unit = {},
        width: Int = 360,
        isCard: Boolean = false,
    ) {
        composeRule.setContent {
            Box(Modifier.width(width.dp)) {
                BookRatingSection(
                    state = state,
                    onSetStars = onSetStars,
                    onEditNote = onEditNote,
                    onRemove = onRemove,
                    onOpenSources = onOpenSources,
                    onRefreshExternal = onRefreshExternal,
                    isCard = isCard,
                )
            }
        }
    }

    @Test
    fun `your rating from Hardcover says so`() {
        show(ready(mine = mine(halfStars = 9, fromHardcover = true)))

        composeRule.onNodeWithText("Rated on Hardcover").assertIsDisplayed()
    }

    @Test
    fun `a rating you set in ListenUp carries no Hardcover line`() {
        show(ready(mine = mine(halfStars = 9)))

        composeRule.onNodeWithText("Rated on Hardcover").assertDoesNotExist()
    }

    @Test
    fun `nothing is drawn while the ratings load`() {
        show(BookRatingsUiState.Loading)

        composeRule.onNodeWithText("Ratings").assertDoesNotExist()
    }

    @Test
    fun `the section is headed Ratings, and an unrated book invites a tap`() {
        show(ready())

        composeRule.onNodeWithText("Ratings").assertIsDisplayed()
        composeRule.onNodeWithText("Your rating").assertIsDisplayed()
        composeRule.onNodeWithText("Not rated").assertIsDisplayed()
        composeRule.onNodeWithText("Tap a star. Drag for half stars.").assertIsDisplayed()
        composeRule.onNodeWithText("Remove").assertDoesNotExist()
    }

    @Test
    fun `a tap on the stars saves straight away`() {
        val saved = mutableListOf<Int>()
        show(ready(), onSetStars = { saved += it })

        composeRule.onNodeWithTag("yourRatingStars").performTouchInput { click(Offset(width * 0.75f, height / 2f)) }

        saved shouldBe listOf(8)
    }

    @Test
    fun `your rating shows its value, your note, Edit note and Remove`() {
        var edits = 0
        var removes = 0
        show(ready(mine = mine(note = "Loved it")), onEditNote = { edits++ }, onRemove = { removes++ })

        // Shown, but hidden from TalkBack: the stars already say "4.5 out of 5 stars".
        composeRule
            .onNodeWithText("4.5")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        composeRule.onNodeWithText("“Loved it”").assertIsDisplayed()
        composeRule.onNodeWithText("Edit note").performClick()
        composeRule.onNodeWithText("Remove").performClick()

        edits shouldBe 1
        removes shouldBe 1
    }

    @Test
    fun `a rating without a note offers Add a note`() {
        show(ready(mine = mine(note = null)))

        composeRule.onNodeWithText("Add a note").assertIsDisplayed()
        composeRule.onNodeWithText("Edit note").assertDoesNotExist()
    }

    @Test
    fun `the ListenUp score is labelled, names its sources, and opens them`() {
        var opened = 0
        show(
            ready(listeners = LISTENERS, external = SCORE, breakdown = listOf(AUDIBLE, HARDCOVER)),
            onOpenSources = { opened++ },
        )

        composeRule
            .onNodeWithContentDescription(
                "ListenUp score: Rated 4.6 out of 5 stars from 12k ratings. Audible, Hardcover, your listeners.",
            ).performClick()

        opened shouldBe 1
    }

    // a11y audit M4 (2026-10-03): the old line said "button" to TalkBack but carried no click action, and was
    // 13–22 dp tall. `performClick()` injects a touch and passes either way, so this asks the semantics tree —
    // what TalkBack, Switch Access and Voice Access actually use.
    @Test
    fun `screen readers can activate the ListenUp score, and its target is at least 48 dp`() {
        var opened = 0
        show(
            ready(listeners = LISTENERS, external = SCORE, breakdown = listOf(AUDIBLE, HARDCOVER)),
            onOpenSources = { opened++ },
        )

        val row = composeRule.onNodeWithTag("listenUpScoreRow")
        row
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher("its click is named 'Show rating sources'") { node ->
                    node.config[SemanticsActions.OnClick].label == "Show rating sources"
                },
            ).assertHeightIsAtLeast(48.dp)
        row.performSemanticsAction(SemanticsActions.OnClick)

        opened shouldBe 1
    }

    @Test
    fun `your listeners read to one decimal, with how many rated`() {
        show(ready(listeners = LISTENERS, external = SCORE, breakdown = listOf(AUDIBLE)))

        composeRule
            .onNodeWithContentDescription("Your listeners: 4.0 out of 5 stars, from 3 ratings")
            .assertIsDisplayed()
    }

    @Test
    fun `while Hardcover is checked, a placeholder holds the score's row`() {
        show(ready(mine = mine(), listeners = ListenerAverage(9.0, 1), isCheckingExternal = true))

        composeRule.onNodeWithTag("checkingHardcoverRow").assertIsDisplayed()
        composeRule.onNodeWithText("Checking Hardcover…", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `with no ratings anywhere, No ratings yet shows and an admin can refresh`() {
        var refreshes = 0
        show(ready(canRefresh = true), onRefreshExternal = { refreshes++ })

        composeRule.onNodeWithText("No ratings yet").assertIsDisplayed()
        composeRule.onNodeWithTag("refreshRatingsInlineButton").assertIsEnabled().performClick()

        refreshes shouldBe 1
    }

    @Test
    fun `a listener sees No ratings yet and no refresh`() {
        show(ready(canRefresh = false))

        composeRule.onNodeWithText("No ratings yet").assertIsDisplayed()
        composeRule.onNodeWithTag("refreshRatingsInlineButton").assertDoesNotExist()
    }

    @Test
    fun `the inline refresh is disabled while one is in flight`() {
        show(ready(canRefresh = true, isRefreshingExternal = true))

        composeRule.onNodeWithTag("refreshRatingsInlineButton").assertIsNotEnabled()
    }

    @Test
    fun `a book only your listeners rated draws no second number, and an admin can still refresh`() {
        show(ready(listeners = LISTENERS, external = LISTENERS_ONLY, canRefresh = true))

        composeRule.onNodeWithTag("listenUpScoreRow").assertDoesNotExist()
        composeRule.onNodeWithText("No ratings yet").assertDoesNotExist()
        composeRule.onNodeWithTag("refreshRatingsInlineButton").assertIsDisplayed()
    }

    @Test
    fun `once a score exists, refresh lives in the sources view, not the section`() {
        show(ready(external = SCORE, breakdown = listOf(AUDIBLE), canRefresh = true))

        composeRule.onNodeWithTag("refreshRatingsInlineButton").assertDoesNotExist()
    }

    @Test
    fun `a narrow block stacks everyone under you`() {
        show(ready(mine = mine(), listeners = LISTENERS, external = SCORE, breakdown = listOf(AUDIBLE)), width = 360)

        val you = composeRule.onNodeWithTag("yourRatingStars").getBoundsInRoot()
        val score = composeRule.onNodeWithTag("listenUpScoreRow").getBoundsInRoot()
        assertTrue("the score row should sit below your stars", score.top >= you.bottom)
    }

    // The block is 800 dp wide only if the window is: a phone-sized Robolectric window caps it.
    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `a wide block splits into you and everyone, side by side`() {
        show(ready(mine = mine(), listeners = LISTENERS, external = SCORE, breakdown = listOf(AUDIBLE)), width = 800)

        val you = composeRule.onNodeWithTag("yourRatingStars").getBoundsInRoot()
        val score = composeRule.onNodeWithTag("listenUpScoreRow").getBoundsInRoot()
        assertTrue("the score row should sit beside your stars", score.left >= you.right)
    }

    @Test
    fun `on a phone, your half is a filled card of its own`() {
        show(ready(mine = mine()))

        composeRule.onNodeWithTag("yourRatingCard").assertIsDisplayed()
    }

    // A-And-Tablet: when the whole section is a card, your half sits flat inside it — never a card in a card.
    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `inside the wide layout's card, your half is not a second card`() {
        show(ready(mine = mine(), external = SCORE, breakdown = listOf(AUDIBLE)), width = 800, isCard = true)

        composeRule.onNodeWithTag("yourRatingStars").assertIsDisplayed()
        composeRule.onNodeWithTag("yourRatingCard").assertDoesNotExist()
    }
}
