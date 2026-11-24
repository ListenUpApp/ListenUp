package com.calypsan.listenup.client.features.contributoredit.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.contributoredit.ContributorCandidate
import com.calypsan.listenup.client.presentation.contributoredit.MAX_MERGE_CANDIDATES
import com.calypsan.listenup.core.ContributorId
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The picker opens unfiltered and shows only the first [MAX_MERGE_CANDIDATES] contributors,
 * alphabetically. On a real library that is thirty A-names, so the person you actually want is
 * almost never on screen — and nothing said so. A reader opened it, did not see their author, and
 * concluded that merging was broken. It was not: the list was simply truncated in silence.
 *
 * These pin the two things that make a truncated list honest — it admits it is partial, and an
 * empty result says why instead of rendering a blank column.
 *
 * JUnit4 + Robolectric, matching `MergeGenreDialogTest`: `createComposeRule()` requires JUnit4, and
 * Robolectric supplies the resource environment so `stringResource` resolves the packaged strings.
 */
@RunWith(RobolectricTestRunner::class)
class ContributorMergeDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a truncated list admits it is partial instead of looking complete`() {
        composeRule.setContent {
            MaterialTheme {
                ContributorMergeDialog(
                    candidates = candidates(MAX_MERGE_CANDIDATES),
                    truncated = true,
                    query = "",
                    onQueryChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(TRUNCATED_HINT).assertIsDisplayed()
    }

    @Test
    fun `a search with no matches says so rather than rendering a blank list`() {
        composeRule.setContent {
            MaterialTheme {
                ContributorMergeDialog(
                    candidates = emptyList(),
                    truncated = false,
                    query = "zzzz",
                    onQueryChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(NO_MATCHES).assertIsDisplayed()
    }

    // The counter-case that stops the notices from being unconditional decoration: a short,
    // complete list is exactly what it appears to be and must say nothing extra.
    @Test
    fun `a complete short list shows neither notice`() {
        composeRule.setContent {
            MaterialTheme {
                ContributorMergeDialog(
                    candidates = candidates(3),
                    truncated = false,
                    query = "",
                    onQueryChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(TRUNCATED_HINT).assertDoesNotExist()
        composeRule.onNodeWithText(NO_MATCHES).assertDoesNotExist()
    }

    @Test
    fun `a chosen target the search has hidden cannot be merged into`() {
        var shown by mutableStateOf(candidates(3))
        val merged = mutableListOf<ContributorId>()
        composeRule.setContent {
            MaterialTheme {
                ContributorMergeDialog(
                    candidates = shown,
                    truncated = false,
                    query = "",
                    onQueryChange = {},
                    onConfirm = { merged += it },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Contributor 0").performClick()
        composeRule.onNodeWithText(CONFIRM).assertIsEnabled()

        shown = candidates(3).drop(1)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(CONFIRM).assertIsNotEnabled()
        merged shouldBe emptyList()
    }

    @Test
    fun `candidates are radio rows that announce which one is chosen`() {
        composeRule.setContent {
            MaterialTheme {
                ContributorMergeDialog(
                    candidates = candidates(2),
                    truncated = false,
                    query = "",
                    onQueryChange = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Contributor 1").performClick()

        composeRule
            .onNode(hasText("Contributor 1") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
        composeRule
            .onNode(hasText("Contributor 0") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsNotSelected()
    }
}

private fun candidates(count: Int): List<ContributorCandidate> =
    List(count) { index ->
        ContributorCandidate(
            id = ContributorId("contributor-$index"),
            displayName = "Contributor $index",
            bookCount = 0,
        )
    }

private const val TRUNCATED_HINT = "Showing the first 30. Search to find anyone else."
private const val NO_MATCHES = "No contributors match that search."
private const val CONFIRM = "Add alias"
