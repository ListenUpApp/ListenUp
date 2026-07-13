package com.calypsan.listenup.client.design.reorderable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.Haptics
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Behavioral coverage for [ReorderableList]'s gesture plumbing: pickup fires
 * [Haptics.thresholdActivate], a completed drag calls [Haptics.commit] and [onMove] with the
 * negotiator's result, and a drag that resolves to a no-op fires neither [Haptics.commit] nor
 * [onMove]. Pixel-level drop-slot math is [ReorderNegotiatorFlatTest]/[ReorderNegotiatorNestedTest]'s
 * job — this test only proves the composable wires gestures to the negotiator and callbacks
 * correctly.
 */
@RunWith(RobolectricTestRunner::class)
class ReorderableListTest {
    @get:Rule val composeRule = createComposeRule()

    private class RecordingHaptics : Haptics {
        val events = mutableListOf<String>()

        override fun selectionTick() {
            events += "selectionTick"
        }

        override fun toggle(on: Boolean) {
            events += "toggle($on)"
        }

        override fun longPress() {
            events += "longPress"
        }

        override fun thresholdActivate() {
            events += "thresholdActivate"
        }

        override fun commit() {
            events += "commit"
        }
    }

    @Test
    fun `dragging an item down past a sibling emits the resolved ReorderMove and commits haptics`() {
        val haptics = RecordingHaptics()
        val moves = mutableListOf<ReorderMove>()
        val nodes =
            listOf(
                ReorderNode(id = "a", parentId = null, canHaveChildren = false),
                ReorderNode(id = "b", parentId = null, canHaveChildren = false),
                ReorderNode(id = "c", parentId = null, canHaveChildren = false),
            )

        composeRule.setContent {
            CompositionLocalProvider(LocalHaptics provides haptics) {
                ReorderableList(
                    nodes = nodes,
                    onMove = { moves += it },
                    itemContent = { nodeId ->
                        Box(Modifier.testTag("item_$nodeId").height(48.dp)) { Text(nodeId) }
                    },
                )
            }
        }

        // Single continuous touch: press, hold past the long-press timeout (triggers
        // detectDragGesturesAfterLongPress's pickup), then drag down two full slot-heights, release.
        composeRule.onNodeWithTag("item_a").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, 48.dp.toPx() * 2))
            up()
        }

        composeRule.runOnIdle {
            haptics.events.first() shouldBe "thresholdActivate"
            haptics.events.last() shouldBe "commit"
            moves shouldBe listOf(ReorderMove(movedId = "a", newParentId = null, newIndex = 2))
        }
    }

    @Test
    fun `a drag that resolves back to the original slot fires no onMove and no commit`() {
        val haptics = RecordingHaptics()
        val moves = mutableListOf<ReorderMove>()
        val nodes = listOf(ReorderNode(id = "only", parentId = null, canHaveChildren = false))

        composeRule.setContent {
            CompositionLocalProvider(LocalHaptics provides haptics) {
                ReorderableList(
                    nodes = nodes,
                    onMove = { moves += it },
                    itemContent = { nodeId -> Box(Modifier.testTag("item_$nodeId").height(48.dp)) { Text(nodeId) } },
                )
            }
        }

        composeRule.onNodeWithTag("item_only").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, 4.dp.toPx())) // tiny jitter, same slot
            up()
        }

        composeRule.runOnIdle {
            haptics.events shouldBe listOf("thresholdActivate")
            moves shouldBe emptyList()
        }
    }
}
