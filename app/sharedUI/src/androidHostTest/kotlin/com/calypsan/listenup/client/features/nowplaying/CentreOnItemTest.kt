package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.nowplaying.components.centreOn
import com.calypsan.listenup.client.features.nowplaying.components.isItemFullyVisible
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §3.11's two facts about an always-visible chapter list: whether a row is wholly in view (the follow rule's only
 * input), and centring a row — jumping on open, gliding when following.
 */
@Config(qualifiers = "w400dp-h800dp")
@RunWith(RobolectricTestRunner::class)
class CentreOnItemTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var state: LazyListState
    private lateinit var scope: CoroutineScope

    private fun list() {
        composeRule.setContent {
            state = rememberLazyListState()
            scope = rememberCoroutineScope()
            LazyColumn(state = state, modifier = Modifier.size(width = 340.dp, height = VIEWPORT_DP.dp)) {
                items(ROWS) { Box(Modifier.fillMaxWidth().height(ROW_DP.dp)) }
            }
        }
        composeRule.waitForIdle()
    }

    private fun centreOffsetPx(index: Int): Int {
        val layout = state.layoutInfo
        val item = layout.visibleItemsInfo.first { it.index == index }
        val viewportCentre = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
        return abs(item.offset + item.size / 2 - viewportCentre)
    }

    private val rowPx: Int get() = with(composeRule.density) { ROW_DP.dp.roundToPx() }

    @Test
    fun `a row at the top is fully in view, and one far below is not`() {
        list()
        state.isItemFullyVisible(0) shouldBe true
        state.isItemFullyVisible(20) shouldBe false
    }

    @Test
    fun `a jump centres the row`() {
        list()
        composeRule.runOnIdle { scope.launch { state.centreOn(38, animate = false) } }
        composeRule.waitForIdle()
        state.isItemFullyVisible(38) shouldBe true
        (centreOffsetPx(38) < rowPx) shouldBe true
    }

    @Test
    fun `a row cut by the viewport's edge is not fully in view`() {
        list()
        composeRule.runOnIdle { scope.launch { state.scrollToItem(0, scrollOffset = rowPx / 2) } }
        composeRule.waitForIdle()
        state.isItemFullyVisible(0) shouldBe false
        state.isItemFullyVisible(1) shouldBe true
    }

    @Test
    fun `a glide ends in the same place as a jump`() {
        list()
        composeRule.mainClock.autoAdvance = true
        composeRule.runOnIdle { scope.launch { state.centreOn(38, animate = true) } }
        composeRule.waitForIdle()
        state.isItemFullyVisible(38) shouldBe true
        (centreOffsetPx(38) < rowPx) shouldBe true
    }

    @Test
    fun `a glide to a laid-out row also centres it`() {
        list()
        composeRule.mainClock.autoAdvance = true
        composeRule.runOnIdle { scope.launch { state.centreOn(6, animate = true) } }
        composeRule.waitForIdle()
        (centreOffsetPx(6) < rowPx) shouldBe true
    }

    private companion object {
        const val ROWS = 75
        const val ROW_DP = 56
        const val VIEWPORT_DP = 600
    }
}
