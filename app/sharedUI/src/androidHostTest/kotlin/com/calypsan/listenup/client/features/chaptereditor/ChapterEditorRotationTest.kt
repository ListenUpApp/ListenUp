package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.StateRestorationTester
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

/**
 * Rotation recreates the activity. A chapter-editor dialog the user had open — and what they had
 * typed into it — must come back with it, not close and drop the text.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterEditorRotationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val restorationTester by lazy { StateRestorationTester(composeRule) }

    private val chapters =
        listOf(
            Chapter(id = "c1", title = "Credits", duration = 1_000L, startTime = 0L),
            Chapter(id = "c2", title = "Part II", duration = 1_000L, startTime = 1_000L, partTitle = "Part II"),
        )

    @Test
    fun `an open rename dialog keeps its typed title across rotation`() {
        hostRowActions(open = RowAction.Renaming("c1"))
        composeRule.onNodeWithText("Credits").performTextReplacement("Opening Credits")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Rename chapter").assertExists()
        editableTextOf("Chapter title") shouldBe "Opening Credits"
    }

    @Test
    fun `an open delete confirmation survives rotation`() {
        hostRowActions(open = RowAction.Deleting("c1"))

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Delete this chapter?").assertExists()
    }

    @Test
    fun `an open marker sheet survives rotation`() {
        hostRowActions(open = RowAction.EditingMarker(ChapterMarkerEdit("c2", ChapterMarkerTier.Part, isNew = false)))

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Edit part").assertExists()
    }

    @Test
    fun `the marker sheet keeps its typed name across rotation`() {
        restorationTester.setContent {
            MaterialTheme {
                ChapterMarkerSheetContent(
                    edit = ChapterMarkerEdit("c2", ChapterMarkerTier.Part, isNew = false),
                    initialName = "Part II",
                    chapters = chapters,
                    onSave = { _, _ -> },
                    onRemove = {},
                )
            }
        }
        composeRule.onNodeWithText("Part II").performTextReplacement("The Middle")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("The Middle").assertExists()
    }

    /** The screen's own [rememberRowAction] and [RowActionDialogs], opened through a stand-in row control. */
    private fun hostRowActions(open: RowAction) {
        restorationTester.setContent {
            MaterialTheme {
                var action by rememberRowAction()
                TextButton(onClick = { action = open }) { Text(OPEN) }
                RowActionDialogs(
                    action = action,
                    chapterOf = { id -> chapters.firstOrNull { it.id == id } },
                    onAction = { action = it },
                    onRename = { _, _ -> },
                    onRetime = { _, _ -> },
                    onDelete = {},
                    markerSheet = { edit ->
                        ChapterMarkerSheetContent(
                            edit = edit,
                            initialName = "Part II",
                            chapters = chapters,
                            onSave = { _, _ -> },
                            onRemove = {},
                        )
                    },
                )
            }
        }
        composeRule.onNodeWithText(OPEN).performClick()
        composeRule.waitForIdle()
    }

    private fun editableTextOf(label: String): String =
        composeRule
            .onNodeWithText(label)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    private companion object {
        const val OPEN = "Open the row action"
    }
}
