package com.calypsan.listenup.client.features.contributors

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.testing.Windows
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Book Detail's All credits (spec D8): everyone credited, grouped under their role — the heading says who wrote,
 * who read, who translated — as web's dialog and iOS's Cast & Credits do. A person in two roles is under both.
 */
@RunWith(RobolectricTestRunner::class)
class FullCastByRoleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val everyone =
        listOf(
            BookContributor(id = "a1", name = "Ann Author", roles = listOf("author")),
            BookContributor(id = "n1", name = "Ned Narrator", roles = listOf("narrator")),
            BookContributor(id = "n2", name = "Nia Narrator", roles = listOf("narrator")),
            BookContributor(id = "t1", name = "Tess Both", roles = listOf("translator", "narrator")),
        )

    private fun show() {
        composeRule.setContent {
            MaterialTheme {
                FullCastSheetFor(
                    role = CastRole.All,
                    authors = emptyList(),
                    narrators = emptyList(),
                    onContributorClick = {},
                    onDismiss = {},
                    everyone = everyone,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertGroupedByRole() {
        listOf("Author", "Narrators", "Translator").forEach { role ->
            composeRule.onNode(isHeading() and hasText(role)).assertExists()
        }
        composeRule.onAllNodesWithText("Tess Both").fetchSemanticsNodes().size shouldBe 2
    }

    @Test
    @Config(qualifiers = Windows.PHONE)
    fun `on a phone the sheet heads each role and lists its people under it`() {
        show()
        assertGroupedByRole()
    }

    @Test
    @Config(qualifiers = Windows.TABLET)
    fun `on a tablet the dialog does the same`() {
        show()
        assertGroupedByRole()
    }
}
