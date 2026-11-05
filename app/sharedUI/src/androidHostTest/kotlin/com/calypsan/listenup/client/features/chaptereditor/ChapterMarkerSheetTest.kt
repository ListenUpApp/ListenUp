package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterMarkerTier
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The sheet a marker is edited in: its name, where it starts, and the way to take it off. */
@RunWith(RobolectricTestRunner::class)
class ChapterMarkerSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chapters =
        listOf(
            Chapter(id = "c1", title = "Credits", duration = 1_000L, startTime = 0L),
            Chapter(id = "c2", title = "Part II", duration = 1_000L, startTime = 1_000L, partTitle = "Part II"),
            Chapter(id = "c3", title = "Dawn", duration = 1_000L, startTime = 2_000L),
            Chapter(id = "c4", title = "Part III", duration = 1_000L, startTime = 3_000L, partTitle = "Part III"),
        )

    private val calls = mutableListOf<String>()

    private fun render(
        chapterId: String = "c2",
        isNew: Boolean = false,
        name: String = "Part II",
    ) {
        composeRule.setContent {
            MaterialTheme {
                ChapterMarkerSheetContent(
                    edit = ChapterMarkerEdit(chapterId, ChapterMarkerTier.Part, isNew),
                    initialName = name,
                    chapters = chapters,
                    onSave = { title, startsAt -> calls += "save:$title:$startsAt" },
                    onRemove = { calls += "remove" },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `an existing marker shows its name, its start, and the way to remove it`() {
        render()

        composeRule.onNodeWithText("Edit part").assertIsDisplayed()
        composeRule.onNodeWithText("Part II").assertIsDisplayed()
        composeRule.onNodeWithText("Chapter 2, Part II · 0:00:01").assertIsDisplayed()
        composeRule.onNodeWithText("Remove marker").assertIsDisplayed()
    }

    @Test
    fun `a new marker has nothing to remove and nowhere else to start`() {
        render(chapterId = "c3", isNew = true, name = "Part 3")

        composeRule.onNodeWithText("New part").assertIsDisplayed()
        composeRule.onNodeWithText("Remove marker").assertDoesNotExist()
        composeRule.onNodeWithText("Move").assertDoesNotExist()
    }

    @Test
    fun `Save carries the typed name and the unchanged start`() {
        render()

        composeRule.onNodeWithText("Part II").performTextReplacement("  The Middle ")
        composeRule.onNodeWithText("Save").performClick()

        calls shouldBe listOf("save:The Middle:c2")
    }

    @Test
    fun `a blank name cannot be saved`() {
        render()

        composeRule.onNodeWithText("Part II").performTextReplacement("   ")

        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `Move lists only chapters that do not already open a part`() {
        render()

        composeRule.onNodeWithText("Move").performClick()

        composeRule.onNodeWithText("Choose a chapter").assertIsDisplayed()
        composeRule.onNodeWithText("Credits").assertIsDisplayed()
        composeRule.onNodeWithText("Dawn").assertIsDisplayed()
        composeRule.onNodeWithText("Part III").assertDoesNotExist()
    }

    @Test
    fun `choosing a chapter returns to the form, and Save carries the new start`() {
        render()

        composeRule.onNodeWithText("Move").performClick()
        composeRule.onNodeWithText("Dawn").performClick()

        composeRule.onNodeWithText("Chapter 3, Dawn · 0:00:02").assertIsDisplayed()
        composeRule.onNodeWithText("Save").performClick()
        calls shouldBe listOf("save:Part II:c3")
    }

    @Test
    fun `Remove marker removes it`() {
        render()

        composeRule.onNodeWithText("Remove marker").performClick()

        calls shouldBe listOf("remove")
    }
}
