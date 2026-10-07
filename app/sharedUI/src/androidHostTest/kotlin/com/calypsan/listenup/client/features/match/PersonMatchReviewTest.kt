package com.calypsan.listenup.client.features.match

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.presentation.match.PersonApplySummary
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import com.calypsan.listenup.client.testing.Windows
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Person Review on a phone, from fixed state: photo and biography chosen apart, and the Apply bar. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class PersonMatchReviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = RecordingPersonMatchActions()

    private fun setReview(state: PersonReviewUiState) {
        composeRule.setContent {
            MaterialTheme {
                PersonReviewPane(
                    state = state,
                    contributorId = PersonMatchFixtures.CONTRIBUTOR_ID,
                    header = PersonMatchFixtures.ray,
                    role = ContributorRole.NARRATOR,
                    viewerId = MatchFixtures.VIEWER_ID,
                    isTwoPane = false,
                    actions = actions,
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        composeRule
            .onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo))
            .performScrollToNode(hasText(text, substring = true))
    }

    private fun biographyCheckbox() =
        composeRule.onNode(isToggleable() and hasContentDescription("Apply biography", substring = true))

    @Test
    fun `Review opens on the person, says what will change, and has a heading for every section`() {
        setReview(PersonMatchFixtures.ready)

        composeRule.onNodeWithText("Ray Porter · narrator · Review").assertIsDisplayed()
        composeRule.onNode(hasText("Ray Porter") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Strong match").assertIsDisplayed()
        composeRule.onNodeWithText("Narrator · from Beacon").assertIsDisplayed()
        composeRule.onNodeWithText("Narrated 5 books in your library").assertIsDisplayed()
        composeRule.onNode(hasText("What will change") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Photo and biography, chosen separately.").assertIsDisplayed()
        scrollTo("Photo")
        composeRule.onNode(hasText("Photo") and isHeading()).assertIsDisplayed()
        scrollTo("Biography")
        composeRule.onNode(hasText("Biography") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun `the photo is one radio group, Keep current or a source, the proposed one chosen`() {
        setReview(PersonMatchFixtures.ready)
        scrollTo("Keep current")

        composeRule
            .onNodeWithContentDescription("Photo from Beacon")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
        composeRule.onNodeWithContentDescription("Keep current photo").assertIsNotSelected().performClick()

        actions.calls shouldContain "photo:${ImageChoice.KeepCurrent}"
    }

    @Test
    fun `a biography that fills a gap starts ticked, shows yours and the proposal, and unticks`() {
        setReview(PersonMatchFixtures.ready)
        scrollTo(PersonMatchFixtures.PROPOSED_BIO)

        biographyCheckbox().assertIsOn()
        composeRule.onNodeWithText("Fills a gap").assertIsDisplayed()
        composeRule.onNodeWithText(PersonMatchFixtures.PROPOSED_BIO).assertIsDisplayed()
        biographyCheckbox().performClick()

        actions.calls shouldContain "bio:false"
    }

    @Test
    fun `a biography you edited by hand starts unticked, flagged, with a source switch that can keep yours`() {
        setReview(PersonMatchFixtures.ready.copy(biography = PersonMatchFixtures.handEditedBiography))
        scrollTo("Ray narrates.")

        biographyCheckbox().assertIsOff()
        composeRule.onNodeWithText("You edited this").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Keep yours").assertIsOn()
        composeRule.onNodeWithContentDescription("Atlas").performClick()

        actions.calls shouldContain "bioSource:${FieldChoice.Option("bio-atlas")}"
        scrollTo("Edited by you")
        composeRule.onNodeWithText("Edited by you. Kept unless you tick it.").assertIsDisplayed()
    }

    @Test
    fun `a biography already the same says so and has nothing to tick`() {
        setReview(
            PersonMatchFixtures.ready.copy(
                biography = PersonMatchFixtures.biography.copy(state = FieldState.SAME, choice = FieldChoice.KeepCurrent),
            ),
        )
        scrollTo("Your biography already matches.")

        composeRule.onNodeWithText("Your biography already matches.").assertIsDisplayed()
        composeRule
            .onAllNodes(isToggleable() and hasContentDescription("Apply biography", substring = true))
            .fetchSemanticsNodes()
            .size shouldBe 0
    }

    @Test
    fun `the Apply bar follows the ticks`() {
        setReview(PersonMatchFixtures.ready)
        composeRule.onNodeWithTag(APPLY_SUMMARY_TAG).assertTextEquals("Photo · biography")
        composeRule.onNodeWithTag(APPLY_BUTTON_TAG).performClick()
        actions.calls shouldContain "apply"
    }

    @Test
    fun `with only the biography ticked the bar says Biography`() {
        setReview(PersonMatchFixtures.ready.copy(applyBar = PersonApplySummary(photo = false, biography = true, sources = emptyList())))

        composeRule.onNodeWithTag(APPLY_SUMMARY_TAG).assertTextEquals("Biography")
    }

    @Test
    fun `with nothing ticked the bar says so and Apply is disabled`() {
        setReview(PersonMatchFixtures.ready.copy(applyBar = PersonApplySummary(photo = false, biography = false, sources = emptyList())))

        composeRule.onNodeWithTag(APPLY_SUMMARY_TAG).assertTextEquals("Nothing selected")
        composeRule.onNodeWithTag(APPLY_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun `an Apply that failed says nothing was changed`() {
        setReview(PersonMatchFixtures.ready.copy(applyError = TransportError.NetworkUnavailable()))

        composeRule.onNodeWithText("Nothing was changed.", substring = true).assertIsDisplayed()
    }
}
