package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * Verifies that [WavySeekBar] exposes the accessibility semantics required for
 * TalkBack users to perceive and operate the scrubber.
 *
 * A seekbar in Compose Accessibility is signalled by two properties working
 * together (there is no Role.Slider in Compose UI 1.11):
 *
 *  - `ProgressBarRangeInfo` — exposes the current position within the range;
 *    TalkBack reads this aloud and uses it to derive the seekbar widget class.
 *  - `SemanticsActions.SetProgress` — lets TalkBack move the thumb and fire the
 *    `onSeek` callback.
 *  - `StateDescription` — surfaces a human-readable position string.
 *
 * JUnit4 + Robolectric (consistent with [PhaseABoundarySuiteTest]).
 */
@RunWith(RobolectricTestRunner::class)
class WavySeekBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `WavySeekBar exposes ProgressBarRangeInfo matching supplied progress`() {
        val progress = 0.4f

        composeRule.setContent {
            WavySeekBar(
                progress = progress,
                onSeek = {},
                modifier = Modifier.testTag(TEST_TAG),
            )
        }

        composeRule
            .onNodeWithTag(TEST_TAG)
            .assert(
                SemanticsMatcher("ProgressBarRangeInfo.current ≈ $progress") { node ->
                    val info: ProgressBarRangeInfo? =
                        node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
                    info != null && abs(info.current - progress) < 0.001f
                },
            )
    }

    @Test
    fun `WavySeekBar has SetProgress action`() {
        composeRule.setContent {
            WavySeekBar(
                progress = 0.3f,
                onSeek = {},
                modifier = Modifier.testTag(TEST_TAG),
            )
        }

        composeRule
            .onNodeWithTag(TEST_TAG)
            .assert(
                SemanticsMatcher("has SetProgress action") { node ->
                    node.config.getOrNull(SemanticsActions.SetProgress) != null
                },
            )
    }

    @Test
    fun `WavySeekBar SetProgress action fires onSeek with target value`() {
        var receivedValue = Float.NaN

        composeRule.setContent {
            WavySeekBar(
                progress = 0.2f,
                onSeek = { receivedValue = it },
                modifier = Modifier.testTag(TEST_TAG),
            )
        }

        val targetValue = 0.75f
        composeRule
            .onNodeWithTag(TEST_TAG)
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                withClue("SetProgress action did not report success") {
                    setProgress(targetValue) shouldBe true
                }
            }

        withClue("onSeek was not called after SetProgress action") {
            !receivedValue.isNaN() shouldBe true
        }
        withClue("SetProgress delivered wrong value to onSeek") {
            (abs(receivedValue - targetValue) < 0.001f) shouldBe true
        }
    }

    @Test
    fun `WavySeekBar state description reflects supplied description`() {
        val description = "3:30 of 42:00"

        composeRule.setContent {
            WavySeekBar(
                progress = 0.1f,
                onSeek = {},
                stateDescription = description,
                modifier = Modifier.testTag(TEST_TAG),
            )
        }

        composeRule
            .onNodeWithTag(TEST_TAG)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    description,
                ),
            )
    }

    private companion object {
        const val TEST_TAG = "wavy_seek_bar_test"
    }
}
