package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.koin.core.context.stopKoin
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A reader's progress is a bar, not a number; the number is spoken (spec §5.2 Reader bars). */
@RunWith(RobolectricTestRunner::class)
class ReaderRowProgressTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The avatar resolves its collaborators through a composition Koin, which registers globally. */
    @Before
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun row(
        name: String,
        pct: Int,
    ) = ReaderRowUi(userId = name.lowercase(), name = name, isReading = true, progressPct = pct, finishedWhen = null)

    @Test
    fun `a reading row draws a bar and says Listening now, and speaks the percent`() {
        composeRule.setContent {
            WithAvatarDependencies { ReaderRow(reader = row("Maya", 64), onUserClick = {}) }
        }
        composeRule.onAllNodesWithText("64%", substring = true, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithText("Listening now", useUnmergedTree = true).assertExists()
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size shouldBe 1
        composeRule.onNode(hasContentDescription("Maya, 64 percent")).assertExists()
    }

    @Test
    fun `six readers show three rows and See all 6 readers`() {
        var seeAll = false
        composeRule.setContent {
            WithAvatarDependencies {
                BookReadersContent(
                    readers = List(6) { row("R$it", 10) }.take(3),
                    listeningNowCount = 6,
                    totalCount = 6,
                    isCard = true,
                    onUserClick = {},
                    onSeeAllClick = { seeAll = true },
                )
            }
        }
        composeRule.onNodeWithText("See all 6 readers").performClick()
        seeAll shouldBe true
    }
}
