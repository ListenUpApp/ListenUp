package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A flow step tells TalkBack where it stands — done, in progress, or not started — rather than
 * leaving that to the colour of its mark and the visible "In progress" pill.
 */
@RunWith(RobolectricTestRunner::class)
class FlowStepRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `each step announces its state`() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    FlowStepRow(FlowStepState.DONE, ListenUpIcons.Info, "Upload", "Files are on the server")
                    FlowStepRow(FlowStepState.ACTIVE, ListenUpIcons.Info, "Review", "Check what was found")
                    FlowStepRow(FlowStepState.TODO, ListenUpIcons.Info, "Apply", "Add the books")
                }
            }
        }

        composeRule.onNodeWithText("Upload").assert(hasStateDescription("Done"))
        composeRule.onNodeWithText("Review").assert(hasStateDescription("In progress"))
        composeRule.onNodeWithText("Apply").assert(hasStateDescription("Not started"))
    }

    @Test
    fun `the in-progress pill is not read twice`() {
        composeRule.setContent {
            MaterialTheme {
                FlowStepRow(FlowStepState.ACTIVE, ListenUpIcons.Info, "Review", "Check what was found")
            }
        }

        // The merged node is what TalkBack reads: the title and subtitle, with the state spoken once.
        composeRule
            .onNodeWithText("Review")
            .assert(
                SemanticsMatcher("reads no pill text") { node ->
                    node.config[SemanticsProperties.Text].none { it.text.equals("In progress", ignoreCase = true) }
                },
            )
    }

    private fun hasStateDescription(expected: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)
}
