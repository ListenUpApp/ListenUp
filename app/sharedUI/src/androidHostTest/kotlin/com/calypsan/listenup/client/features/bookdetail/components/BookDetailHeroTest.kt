package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.features.contributors.CastRole
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookDetailHeroTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setHero(
        subtitle: String? = null,
        abridged: Boolean = false,
        narrators: List<BookContributor> = listOf(BookContributor(id = "n1", name = "Michael Kramer")),
        onShowCast: (CastRole) -> Unit = {},
    ) = composeRule.setContent {
        MaterialTheme {
            BookDetailHeroBand(
                coverPath = "/tmp/cover-b1.webp",
                coverHash = null,
                bookId = "b1",
                title = "The Way of Kings",
                subtitle = subtitle,
                seriesPaths = emptyList(),
                authors = listOf(BookContributor(id = "a1", name = "Brandon Sanderson")),
                narrators = narrators,
                durationMs = (45 * 60 + 30) * 60_000L,
                year = 2010,
                abridged = abridged,
                onContributorClick = {},
                onSeriesClick = {},
                onShowCast = onShowCast,
            )
        }
    }

    @Test
    fun `the title is the page's only heading named after the book`() {
        setHero()
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasTextExactly("The Way of Kings"))
            .assertCountEquals(1)
    }

    @Test
    fun `the stat chips read length, year and abridgement`() {
        setHero()
        composeRule.onNodeWithText("45h 30m").assertExists()
        composeRule.onNodeWithText("2010").assertExists()
        composeRule.onNodeWithText("Unabridged").assertExists()
    }

    @Test
    fun `an abridged book says so`() {
        setHero(abridged = true)
        composeRule.onNodeWithText("Abridged").assertExists()
        composeRule.onNodeWithText("Unabridged").assertDoesNotExist()
    }

    @Test
    fun `a subtitle shows under the title (b9)`() {
        setHero(subtitle = "Book One of the Stormlight Archive")
        composeRule.onNodeWithText("Book One of the Stormlight Archive").assertExists()
    }

    @Test
    fun `narrators read as Read by, and All credits opens the whole cast`() {
        var opened: CastRole? = null
        setHero(onShowCast = { opened = it })
        composeRule.onNodeWithText("Read by", substring = true).assertExists()
        composeRule.onNodeWithText("All credits").performClick()
        opened shouldBe CastRole.All
    }

    @Test
    fun `All credits is there even when no narrator is credited`() {
        setHero(narrators = emptyList())
        composeRule.onNodeWithText("Read by", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("All credits").assertExists()
    }
}
