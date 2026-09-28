package com.calypsan.listenup.client.features.contributors

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import com.calypsan.listenup.client.domain.model.BookContributor
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sheets use Material's own drag handle, not a painted pill. The painted one was a bare Surface:
 * TalkBack could not find it, and it carried none of the handle's dismiss / expand / collapse
 * actions, so a user who cannot drag had no handle at all.
 */
@RunWith(RobolectricTestRunner::class)
class FullCastSheetDragHandleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the sheet's drag handle is a named control that can dismiss the sheet`() {
        var dismissals = 0
        composeRule.setContent {
            MaterialTheme {
                FullCastSheet(
                    title = "Narrators",
                    countText = "2 narrators",
                    contributors = listOf(BookContributor("n1", "Ada"), BookContributor("n2", "Grace")),
                    onContributorClick = {},
                    onDismiss = { dismissals++ },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithContentDescription("Drag handle", substring = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss)
        composeRule.waitForIdle()

        dismissals shouldBe 1
    }
}
