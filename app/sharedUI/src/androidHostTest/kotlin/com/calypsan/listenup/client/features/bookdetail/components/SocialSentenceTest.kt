package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.bookdetail.ReaderRef
import com.calypsan.listenup.client.presentation.bookdetail.ReadersSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The hero's social sentence (spec §2.10): two names a clause, listening first, built from the catalog. */
@RunWith(RobolectricTestRunner::class)
class SocialSentenceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun refs(vararg names: String) = names.map { ReaderRef(userId = it.lowercase(), displayName = it) }

    private fun assertSentence(
        summary: ReadersSummary,
        expected: String,
    ) {
        composeRule.setContent { MaterialTheme { Text(socialSentence(summary).text) } }
        composeRule.onNodeWithText(expected).assertExists()
    }

    @Test
    fun `two listening and one finished`() =
        assertSentence(
            ReadersSummary(
                listening = refs("Maya", "Priya"),
                finished = refs("Tom"),
                avatars = refs("Maya", "Priya", "Tom"),
                othersCount = 0,
            ),
            "Maya and Priya are listening · Tom finished it",
        )

    @Test
    fun `more than two in a clause counts the rest`() =
        assertSentence(
            ReadersSummary(
                listening = refs("Maya", "Priya", "A", "B", "C"),
                finished = emptyList(),
                avatars = refs("Maya", "Priya", "A"),
                othersCount = 3,
            ),
            "Maya, Priya and 3 others are listening",
        )

    @Test
    fun `one other is singular`() =
        assertSentence(
            ReadersSummary(
                listening = refs("Maya", "Priya", "A"),
                finished = emptyList(),
                avatars = refs("Maya", "Priya", "A"),
                othersCount = 1,
            ),
            "Maya, Priya and 1 other are listening",
        )

    @Test
    fun `only finished`() =
        assertSentence(
            ReadersSummary(listening = emptyList(), finished = refs("Tom"), avatars = refs("Tom"), othersCount = 0),
            "Tom finished it",
        )

    @Test
    fun `one listening`() =
        assertSentence(
            ReadersSummary(listening = refs("Maya"), finished = emptyList(), avatars = refs("Maya"), othersCount = 0),
            "Maya is listening",
        )
}
