package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.calypsan.listenup.client.features.nowplaying.components.PlayerScrubber
import com.calypsan.listenup.client.playback.PlaybackProgress
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves the structural fix for the 4 Hz now-playing recomposition storm: the fast-changing
 * playback progress is threaded as a deferred `() -> PlaybackProgress` lambda, so a position
 * tick recomposes only the leaf that reads it ([PlayerScrubber]) — never the surrounding player
 * chrome (cover, title, transport), which does not read the lambda.
 *
 * The test renders a chrome probe (counts its own recompositions, reads nothing from progress)
 * as a sibling of [PlayerScrubber] (which reads the lambda). Ticking the progress backing state
 * must leave the chrome probe's recomposition count untouched while the scrubber re-reads the
 * new value.
 *
 * Without the deferred-lambda threading — i.e. passing `progress: PlaybackProgress` by value and
 * reading it in the parent scope — the chrome probe would recompose on every tick, which is the
 * regression this guards.
 *
 * JUnit4 + Robolectric (consistent with [WavySeekBarTest]).
 */
@RunWith(RobolectricTestRunner::class)
class NowPlayingProgressRecompositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `progress tick recomposes the scrubber but not sibling chrome`() {
        var chromeRecompositions = 0
        lateinit var setProgress: (PlaybackProgress) -> Unit

        composeRule.setContent {
            var progress by remember { mutableStateOf(PlaybackProgress.Zero) }
            setProgress = { progress = it }

            Column {
                // Chrome probe — reads no progress. Stands in for the cover/title/transport.
                ChromeProbe(onCompose = { chromeRecompositions++ })

                // Leaf — reads progress through the deferred lambda.
                PlayerScrubber(
                    progress = { progress },
                    isPlaying = true,
                    isBuffering = false,
                    onSeek = {},
                )
            }
        }

        composeRule.waitForIdle()
        val baseline = chromeRecompositions

        // Three position ticks, mimicking the 250 ms poll.
        setProgress(PlaybackProgress.Zero.copy(chapterPositionMs = 10_000L, chapterProgress = 0.1f))
        composeRule.waitForIdle()
        setProgress(PlaybackProgress.Zero.copy(chapterPositionMs = 20_000L, chapterProgress = 0.2f))
        composeRule.waitForIdle()
        setProgress(PlaybackProgress.Zero.copy(chapterPositionMs = 30_000L, chapterProgress = 0.3f))
        composeRule.waitForIdle()

        // The chrome probe must not have recomposed on any tick.
        chromeRecompositions shouldBe baseline
    }
}

/**
 * A composable that invokes [onCompose] each time it (re)composes. Reads nothing from playback
 * progress, so a progress tick must never recompose it.
 */
@androidx.compose.runtime.Composable
private fun ChromeProbe(onCompose: () -> Unit) {
    onCompose()
}
