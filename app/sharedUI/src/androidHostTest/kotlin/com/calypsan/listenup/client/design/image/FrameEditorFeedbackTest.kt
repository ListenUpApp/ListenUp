package com.calypsan.listenup.client.design.image

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.design.components.cookieScallopShape
import com.calypsan.listenup.client.design.haptics.Haptics
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.presentation.imageframe.FrameShape
import io.kotest.matchers.shouldBe
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.edit_frame_preview_profile_label
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The tick a finger feels at an edge, once per arrival; and the live previews with their caption. */
@RunWith(RobolectricTestRunner::class)
class FrameEditorFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    private class CountingHaptics : Haptics {
        var ticks = 0

        override fun selectionTick() {
            ticks++
        }

        override fun press() = Unit

        override fun toggle(on: Boolean) = Unit

        override fun longPress() = Unit

        override fun thresholdActivate() = Unit

        override fun commit() = Unit
    }

    @Test
    fun `running into the top edge ticks once, not once per nudge held there`() {
        val haptics = CountingHaptics()
        compose.setContent {
            CompositionLocalProvider(LocalHaptics provides haptics) {
                MaterialTheme {
                    FrameEditorContent(
                        image = FrameEditorImage.Ready(600, 800, SolidPainter(600, 800)),
                        initialFrame = ImageFrame(0.5, 0.5, 2.0),
                        shape = FrameShape.Circle,
                        confirm = FrameConfirm.Done,
                        previews = emptyList(),
                        onConfirm = {},
                        onCancel = {},
                        onChooseAnother = {},
                    )
                }
            }
        }
        repeat(40) { _ ->
            compose
                .onNodeWithContentDescription("Photo position")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .first { it.label == "Show more of the top" }
                .action()
            compose.waitForIdle()
        }
        haptics.ticks shouldBe 1
    }

    @Test
    fun `the previews carry the caption they are given`() {
        compose.setContent {
            MaterialTheme {
                FrameEditorContent(
                    image = FrameEditorImage.Ready(600, 800, SolidPainter(600, 800)),
                    initialFrame = null,
                    shape = FrameShape.Circle,
                    confirm = FrameConfirm.Done,
                    previews = listOf(FramePreview(cookieScallopShape(), 52.dp), FramePreview(CircleShape, 36.dp)),
                    onConfirm = {},
                    onCancel = {},
                    onChooseAnother = {},
                    previewLabel = Res.string.edit_frame_preview_profile_label,
                )
            }
        }
        compose.onNodeWithText("Profile and lists").assertExists()
    }
}
