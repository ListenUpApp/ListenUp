package com.calypsan.listenup.client.design.image

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.presentation.imageframe.FrameShape
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TalkBack can do everything a finger can on the stage, and the slider speaks the zoom, not a percentage. */
@RunWith(RobolectricTestRunner::class)
class FrameStageAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private var latest: ImageFrame? = null

    private fun show() =
        compose.setContent {
            MaterialTheme {
                FrameEditorContent(
                    image = FrameEditorImage.Ready(600, 800, SolidPainter(600, 800)),
                    initialFrame = ImageFrame(0.5, 0.5, 2.0),
                    shape = FrameShape.Circle,
                    confirm = FrameConfirm.Done,
                    previews = emptyList(),
                    onConfirm = { latest = it },
                    onCancel = {},
                    onChooseAnother = {},
                    onFrameChanged = { latest = it },
                )
            }
        }

    private fun runAction(label: String) {
        val node = compose.onNodeWithContentDescription("Photo position").fetchSemanticsNode()
        node.config[SemanticsActions.CustomActions].first { it.label == label }.action()
        compose.waitForIdle()
    }

    @Test
    fun `the stage offers the seven actions TalkBack users need, in order`() {
        show()
        val actions =
            compose
                .onNodeWithContentDescription("Photo position")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsActions.CustomActions)
                .shouldNotBeNull()
        actions.map { it.label } shouldContainExactly
            listOf(
                "Show more of the top",
                "Show more of the bottom",
                "Show more of the left",
                "Show more of the right",
                "Zoom in",
                "Zoom out",
                "Reset",
            )
    }

    @Test
    fun `Show more of the top moves the view up`() {
        show()
        runAction("Show more of the top")
        latest.shouldNotBeNull().focusY shouldBeLessThan 0.5
    }

    @Test
    fun `Zoom in raises the zoom`() {
        show()
        runAction("Zoom in")
        latest.shouldNotBeNull().zoom shouldBeGreaterThan 2.0
    }

    @Test
    fun `the slider is labelled Zoom and speaks the zoom, not a percentage`() {
        show()
        val slider = compose.onNodeWithContentDescription("Zoom").fetchSemanticsNode()
        slider.config.getOrNull(SemanticsProperties.StateDescription) shouldBe "Zoom 2.0×"
    }
}
