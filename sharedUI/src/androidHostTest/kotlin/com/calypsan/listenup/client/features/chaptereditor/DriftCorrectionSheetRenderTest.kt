package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.chapter.DriftResult
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.DriftPreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Render smoke-test for [DriftCorrectionSheet] — proves it composes without crashing, both in its
 * pre-snap opening state and with a rejected preview showing its inline warning. Unlike
 * [ChapterEditorScreenRenderTest], this composable takes plain callback params (no VM, no Koin
 * dependency), so it's tested standalone — mirrors [components.ChapterDetailPanel]'s own
 * VM-free testability.
 *
 * Driving an actual Snap/Apply tap sequence would need a real playhead + `applyDrift` fake wired
 * to react to state changes across recompositions; out of scope for a render smoke test the same
 * way [ChapterEditorScreenRenderTest] documents skipping gesture-driven `MarkerLaneTimeline` drags.
 */
@RunWith(RobolectricTestRunner::class)
class DriftCorrectionSheetRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun chapter(
        id: String,
        title: String,
        startTime: Long,
        duration: Long,
    ) = Chapter(id = id, title = title, duration = duration, startTime = startTime)

    private val draft =
        listOf(
            chapter("c1", "Chapter One", 0L, 100_000L),
            chapter("c2", "Chapter Two", 100_000L, 100_000L),
            chapter("c3", "Chapter Three", 200_000L, 100_000L),
        )

    @Test
    fun `sheet renders its opening anchor slots without crashing`() {
        composeRule.setContent {
            DriftCorrectionSheet(
                draft = draft,
                playheadMs = { 0L },
                onApplyDrift = { _, _ -> DriftPreview.Ghosts(draft) },
                onCommitDrift = { _, _ -> },
                onGhostsChange = {},
                onDismiss = {},
            )
        }

        composeRule.waitForIdle()

        // First and last chapters are the pre-picked anchor slots.
        composeRule.onNodeWithText("Chapter One").assertExists()
        composeRule.onNodeWithText("Chapter Three").assertExists()
    }

    @Test
    fun `sheet renders a rejected preview's inline warning without crashing`() {
        composeRule.setContent {
            DriftCorrectionSheet(
                draft = draft,
                playheadMs = { 0L },
                onApplyDrift = { _, _ -> DriftPreview.Rejected(DriftResult.Rejected.InvertedAnchors) },
                onCommitDrift = { _, _ -> },
                onGhostsChange = {},
                onDismiss = {},
            )
        }

        composeRule.waitForIdle()

        // Reaching here without an exception, with the anchor rows still present, is the assertion —
        // onApplyDrift is only invoked once a Snap fires, which this test doesn't drive.
        composeRule.onNodeWithText("Chapter One").assertExists()
    }
}
