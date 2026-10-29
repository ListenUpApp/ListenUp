package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.style.TextDirection
import com.calypsan.listenup.client.design.motion.LocalReduceMotion
import com.calypsan.listenup.client.design.text.RightToLeft
import com.calypsan.listenup.client.design.text.textDirection
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one scrubber (§6): chapter-scoped, a slider to TalkBack, seeks once on release. Ports WavySeekBarTest. On
 * Android its active track waves while the book plays, and lies flat while paused or while the handle is held.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackSliderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val seeks = mutableListOf<Float>()
    private var isPlaying by mutableStateOf(false)

    private fun slider(
        enabled: Boolean = true,
        playing: Boolean = false,
    ) {
        isPlaying = playing
        composeRule.setContent {
            MaterialTheme {
                PlaybackSlider(
                    fraction = 0.586f,
                    elapsedMs = 724_000,
                    durationMs = 1_235_000,
                    isPlaying = isPlaying,
                    onSeek = { seeks += it },
                    enabled = enabled,
                )
            }
        }
    }

    private fun waveAmplitude(): Float? =
        composeRule
            .onNodeWithContentDescription("Chapter position")
            .fetchSemanticsNode()
            .config
            .getOrNull(ScrubberWaveAmplitude)

    /**
     * Lets the amplitude's transition finish on a clock the test drives: recompose with the latest change, so the
     * transition starts, then give it time to land.
     */
    private fun settle() {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)
    }

    @Test
    fun `it is a slider named Chapter position, with elapsed of total as its state`() {
        slider()
        composeRule
            .onNodeWithContentDescription("Chapter position")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12:04 of 20:35"))
    }

    @Test
    fun `the times read elapsed and minus remaining`() {
        slider()
        composeRule.onNodeWithText("12:04").assertExists()
        composeRule.onNodeWithText("-8:31").assertExists()
    }

    @Test
    fun `a set-progress action seeks once, to that fraction`() {
        slider()
        composeRule
            .onNodeWithContentDescription("Chapter position")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        composeRule.waitForIdle()
        seeks.size shouldBe 1
        seeks.single() shouldBe (0.25f plusOrMinus 0.001f)
    }

    @Test
    fun `in a right-to-left layout the remaining time keeps its minus before the digits`() {
        composeRule.setContent {
            RightToLeft {
                PlaybackSlider(fraction = 0.586f, elapsedMs = 724_000, durationMs = 1_235_000, isPlaying = false, onSeek = {})
            }
        }
        // Digits and a minus have no direction of their own: laid out right to left, the minus lands after them.
        composeRule.onNodeWithText("-8:31").textDirection() shouldBe TextDirection.ContentOrLtr
        composeRule.onNodeWithText("12:04").textDirection() shouldBe TextDirection.ContentOrLtr
    }

    @Test
    fun `disabled while busy`() {
        slider(enabled = false)
        composeRule.onNodeWithContentDescription("Chapter position").assertIsNotEnabled()
    }

    @Test
    fun `the wave is at full amplitude while the book plays`() {
        composeRule.mainClock.autoAdvance = false
        slider(playing = true)
        settle()
        waveAmplitude() shouldBe 1f
    }

    @Test
    fun `a disabled scrubber lies flat even while the book plays`() {
        composeRule.mainClock.autoAdvance = false
        slider(enabled = false, playing = true)
        settle()
        waveAmplitude() shouldBe 0f
    }

    @Test
    fun `the wave lies flat while paused`() {
        composeRule.mainClock.autoAdvance = false
        slider(playing = false)
        settle()
        waveAmplitude() shouldBe 0f
    }

    @Test
    fun `pausing flattens the wave and playing raises it again`() {
        composeRule.mainClock.autoAdvance = false
        slider(playing = true)
        settle()
        isPlaying = false
        settle()
        waveAmplitude() shouldBe 0f
        isPlaying = true
        settle()
        waveAmplitude() shouldBe 1f
    }

    @Test
    fun `the wave lies flat while the handle is held, and returns after release with one seek`() {
        composeRule.mainClock.autoAdvance = false
        slider(playing = true)
        settle()
        val node = composeRule.onNodeWithContentDescription("Chapter position")
        node.performTouchInput { down(center) }
        settle()
        waveAmplitude() shouldBe 0f
        seeks shouldBe emptyList()

        node.performTouchInput { up() }
        settle()
        waveAmplitude() shouldBe 1f
        seeks.size shouldBe 1
    }

    @Test
    fun `the wave travels by default`() {
        val (before, after) = samplePhase(reduceMotion = false)
        after shouldNotBe before
    }

    @Test
    fun `the wave holds still when animations are removed`() {
        val (before, after) = samplePhase(reduceMotion = true)
        before shouldBe 0f
        after shouldBe 0f
    }

    private fun samplePhase(reduceMotion: Boolean): Pair<Float, Float> {
        var phase = Float.NaN
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                phase = rememberScrubberWavePhase(travels = true).current()
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        val first = phase
        composeRule.mainClock.advanceTimeBy(SETTLE_MS)
        return first to phase
    }

    private companion object {
        const val SETTLE_MS = 1_000L
    }
}
