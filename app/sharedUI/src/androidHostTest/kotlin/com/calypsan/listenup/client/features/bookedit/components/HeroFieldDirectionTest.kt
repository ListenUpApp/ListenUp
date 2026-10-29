package com.calypsan.listenup.client.features.bookedit.components

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextDirection
import com.calypsan.listenup.client.design.text.RightToLeft
import com.calypsan.listenup.client.design.text.textDirection
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The book editor's hero title reads in its own direction, as its placeholder does: "Mistborn, Book 1." in Arabic too. */
@RunWith(RobolectricTestRunner::class)
class HeroFieldDirectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `in a right-to-left layout an English title keeps its full stop at the end`() {
        composeRule.setContent {
            RightToLeft {
                IdentityHeader(
                    coverPath = null,
                    refreshKey = null,
                    title = TITLE,
                    subtitle = "",
                    sortTitle = "",
                    isUploadingCover = false,
                    onTitleChange = {},
                    onSubtitleChange = {},
                    onSortTitleChange = {},
                    onCoverClick = {},
                    onBackClick = {},
                )
            }
        }
        composeRule.onNodeWithText(TITLE).textDirection() shouldBe TextDirection.ContentOrLtr
    }

    private companion object {
        const val TITLE = "Mistborn, Book 1."
    }
}
