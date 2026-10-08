package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.presentation.bookdetail.ChapterUiModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wide Book Detail's chapter pane lives inside the page's vertical scroll. It is a lazy list so
 * a long book composes only the rows on screen, which means it needs a finite height there — without
 * one a lazy list nested in a scroll throws at layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class WideChaptersContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chapters =
        List(300) { i -> ChapterUiModel(id = "c$i", title = "Chapter title ${i + 1}", duration = "10:00", imageUrl = null) }

    @Test
    fun `three hundred chapters inside the page scroll lay out and compose only what is visible`() {
        var expanded by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    WideChaptersContent(
                        chapters = chapters,
                        isExpanded = expanded,
                        onExpand = { expanded = true },
                        listMaxHeight = 600.dp,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Chapter title 10").assertExists()
        composeRule.onNodeWithText("Chapter title 11").assertDoesNotExist()

        composeRule.onNodeWithText("Show all 300 chapters").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Show all 300 chapters").assertDoesNotExist()
        composeRule.onNodeWithText("Chapter title 1").assertIsDisplayed()
        // Lazy: the last row of a 300-chapter book is not composed until it is scrolled to.
        composeRule.onNodeWithText("Chapter title 300").assertDoesNotExist()
    }
}
