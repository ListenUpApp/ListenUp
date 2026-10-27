package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.calypsan.listenup.client.features.nowplaying.NO_COVER
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Performance is part of done: a drag moves every frame and recomposes nothing (canvas `GestureAndroid`). */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class MorphRecompositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rig = MorphRig(composeRule)

    @Test
    fun `thirty frames of a drag recompose neither Now Playing, the bar nor the shell's bottom bar`() {
        rig.show(loadedPlayerState(coverPath = NO_COVER))
        // The drag's first move composes Now Playing and the flying parts once.
        rig.morph.snapDrag(0.01f)
        composeRule.waitForIdle()
        rig.morph.snapDrag(0.02f)
        composeRule.waitForIdle()
        val baseline = rig.compositions.toMap()
        (baseline["CompactNowPlaying"] ?: 0) shouldBeGreaterThan 0

        (1..30).forEach { step ->
            rig.morph.snapDrag(step / 31f)
            composeRule.waitForIdle()
        }

        rig.compositions.toMap() shouldBe baseline
    }
}
