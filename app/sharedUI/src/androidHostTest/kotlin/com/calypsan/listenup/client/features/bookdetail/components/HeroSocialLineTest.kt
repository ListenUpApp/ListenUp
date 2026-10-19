package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.bookdetail.ReaderRef
import com.calypsan.listenup.client.presentation.bookdetail.ReadersSummary
import io.kotest.matchers.shouldBe
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.koin.core.context.stopKoin
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The social line is one link to Readers, read as its sentence; nobody but you draws nothing. */
@RunWith(RobolectricTestRunner::class)
class HeroSocialLineTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The avatar resolves its collaborators through a composition Koin, which registers globally. */
    @Before
    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun refs(vararg names: String) = names.map { ReaderRef(userId = it.lowercase(), displayName = it) }

    @Test
    fun `the line is one link read as a sentence, avatars hidden`() {
        var opened = false
        composeRule.setContent {
            WithAvatarDependencies {
                HeroSocialLine(
                    summary =
                        ReadersSummary(listening = refs("Maya"), finished = emptyList(), avatars = refs("Maya"), othersCount = 0),
                    ringColor = Color.White,
                    onOpenReaders = { opened = true },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Maya is listening").assert(hasClickAction()).performClick()
        opened shouldBe true
        // The avatar's initials are not read: the sentence already names her.
        composeRule.onAllNodesWithText("M").assertCountEquals(0)
    }

    @Test
    fun `nobody but you hides the line`() {
        composeRule.setContent {
            WithAvatarDependencies {
                HeroSocialLine(ReadersSummary(emptyList(), emptyList(), emptyList(), 0), Color.White, onOpenReaders = {})
            }
        }
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }
}
