package com.calypsan.listenup.client.design.motion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [rememberAmbientLoop], the seam the scan spinners run on: a loop that turns by default and
 * holds its rest pose when animations are removed. The system duration scale cannot stop an
 * infinite transition on its own, so without this the spinners would turn regardless.
 */
@RunWith(RobolectricTestRunner::class)
class AmbientLoopTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the loop moves by default`() {
        val samples = sampleLoop(reduceMotion = false)
        samples.second shouldNotBe samples.first
    }

    @Test
    fun `the loop holds its rest pose when animations are removed`() {
        val samples = sampleLoop(reduceMotion = true)
        samples.first shouldBe REST
        samples.second shouldBe REST
    }

    private fun sampleLoop(reduceMotion: Boolean): Pair<Float, Float> {
        var value = Float.NaN
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                value =
                    rememberAmbientLoop(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)),
                        restValue = REST,
                        label = "test",
                    ).value
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        val first = value
        composeRule.mainClock.advanceTimeBy(LOOP_MS / 3L)
        return first to value
    }

    private companion object {
        const val LOOP_MS = 3_000
        const val REST = 0f
    }
}
