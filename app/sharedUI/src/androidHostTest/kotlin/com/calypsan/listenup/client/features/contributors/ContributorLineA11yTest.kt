package com.calypsan.listenup.client.features.contributors

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.testing.assertTouchTargetAtLeast
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Each name on a book's author/narrator line opens that person; TalkBack announces it as a button,
 * and a finger gets a full target even though the line stays one text line high.
 */
@RunWith(RobolectricTestRunner::class)
class ContributorLineA11yTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `each name on the line is a full-size button`() {
        composeRule.setContent {
            MaterialTheme {
                ClickableContributorLine(
                    contributors = listOf(BookContributor(id = "1", name = "Ursula K. Le Guin")),
                    onContributorClick = {},
                    style = MaterialTheme.typography.bodyMedium,
                    nameColor = Color.Black,
                    separatorColor = Color.Black,
                )
            }
        }

        composeRule
            .onNodeWithText("Ursula K. Le Guin")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertTouchTargetAtLeast()
    }
}
