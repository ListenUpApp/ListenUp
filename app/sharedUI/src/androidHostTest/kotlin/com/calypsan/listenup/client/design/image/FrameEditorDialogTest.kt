package com.calypsan.listenup.client.design.image

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.core.image.canonical
import com.calypsan.listenup.client.presentation.imageframe.FrameShape
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A test-only painter with a fixed intrinsic size, standing in for a decoded photo. */
internal class SolidPainter(
    w: Int,
    h: Int,
) : Painter() {
    override val intrinsicSize = Size(w.toFloat(), h.toFloat())

    override fun DrawScope.onDraw() = drawRect(Color.Gray)
}

/** The Adjust photo editor on a phone: a full-screen dialog with a close icon, a filled confirm, the slider and Reset. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class FrameEditorDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private var confirmed: ImageFrame? = null
    private var cancelled = false
    private var choseAnother = false

    private fun show(
        image: FrameEditorImage = FrameEditorImage.Ready(width = 600, height = 800, painter = SolidPainter(600, 800)),
        confirm: FrameConfirm = FrameConfirm.Done,
        initial: ImageFrame? = null,
    ) {
        compose.setContent {
            MaterialTheme {
                FrameEditorContent(
                    image = image,
                    initialFrame = initial,
                    shape = FrameShape.Circle,
                    confirm = confirm,
                    previews = emptyList(),
                    onConfirm = { confirmed = it },
                    onCancel = { cancelled = true },
                    onChooseAnother = { choseAnother = true },
                )
            }
        }
    }

    @Test
    fun `inside a form the confirm says Done`() {
        show(confirm = FrameConfirm.Done)
        compose.onNodeWithText("Done").assertIsEnabled()
        compose.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun `from the menu the confirm says Save`() {
        show(confirm = FrameConfirm.Save)
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Done").assertDoesNotExist()
    }

    @Test
    fun `Done hands back the frame the stage shows, made canonical`() {
        show()
        compose.onNodeWithContentDescription("Photo position").performTouchInput {
            swipe(start = center, end = center + Offset(0f, 120f))
            pinch(
                center - Offset(40f, 0f),
                center - Offset(160f, 0f),
                center + Offset(40f, 0f),
                center + Offset(160f, 0f),
            )
        }
        compose.onNodeWithText("Done").performClick()
        val frame = confirmed.shouldNotBeNull()
        frame.zoom shouldBeGreaterThan 1.0
        frame shouldBe frame.canonical(600.0, 800.0)
    }

    @Test
    fun `Reset is disabled at the centre crop and enabled once moved`() {
        show(initial = null)
        compose.onNodeWithText("Reset").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Photo position").performTouchInput {
            swipe(start = center, end = center + Offset(0f, 120f))
        }
        compose.onNodeWithText("Reset").assertIsEnabled().performClick()
        compose.onNodeWithText("Reset").assertIsNotEnabled()
    }

    @Test
    fun `Cancel hands back nothing`() {
        show()
        compose.onNodeWithContentDescription("Cancel").performClick()
        cancelled shouldBe true
        confirmed shouldBe null
    }

    @Test
    fun `a photo that cannot be opened says so, offers another, and cannot be confirmed`() {
        show(image = FrameEditorImage.CouldNotOpen)
        compose.onNodeWithText("Couldn't open this photo").assertExists()
        compose.onNodeWithText("Try a JPEG, PNG or WebP.").assertExists()
        compose.onNodeWithText("Done").assertIsNotEnabled()
        compose.onNodeWithText("Reset").assertDoesNotExist()
        compose.onNodeWithText("Choose another").performClick()
        choseAnother shouldBe true
    }
}

/** From 600 dp the editor is a basic dialog: Reset, Cancel and the confirm are text-labelled buttons, with no close icon. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h700dp")
class FrameEditorTabletDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `on a wide window the editor is a dialog with text buttons`() {
        compose.setContent {
            MaterialTheme {
                FrameEditorContent(
                    image = FrameEditorImage.Ready(600, 800, SolidPainter(600, 800)),
                    initialFrame = null,
                    shape = FrameShape.Circle,
                    confirm = FrameConfirm.Done,
                    previews = emptyList(),
                    onConfirm = {},
                    onCancel = {},
                    onChooseAnother = {},
                )
            }
        }
        compose.onNodeWithText("Reset").assertExists()
        compose.onNodeWithText("Cancel").assertExists()
        compose.onNodeWithText("Done").assertExists()
        compose.onNodeWithContentDescription("Cancel").assertDoesNotExist()
    }
}
