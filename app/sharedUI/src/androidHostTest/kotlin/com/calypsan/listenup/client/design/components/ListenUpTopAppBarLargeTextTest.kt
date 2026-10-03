package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.testing.AtFontScale
import com.calypsan.listenup.client.testing.assertNotClipped
import com.calypsan.listenup.client.testing.textLayout
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** At the largest font a bar title wraps to a second line, and the bar grows to hold it, instead of "The Two To…". */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class ListenUpTopAppBarLargeTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `at the largest font a long title takes a second line, whole`() {
        composeRule.setContent {
            AtFontScale {
                MaterialTheme {
                    Box(Modifier.width(345.dp)) { ListenUpTopAppBar(title = TITLE, onBack = {}) }
                }
            }
        }

        val title = composeRule.onNodeWithText(TITLE).assertNotClipped()
        title.textLayout().lineCount shouldBe 2
    }

    private companion object {
        const val TITLE = "The Two Towers"
    }
}
