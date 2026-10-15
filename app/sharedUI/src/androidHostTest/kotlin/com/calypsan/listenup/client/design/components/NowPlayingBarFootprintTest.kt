package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.nowplaying.MiniBarHeight
import com.calypsan.listenup.client.features.nowplaying.NowPlayingBar
import com.calypsan.listenup.client.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.client.features.nowplaying.samplePlayerProgress
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a detail screen the phone bar docks flush and runs under the gesture area, so the footprint it reports to
 * [nowPlayingClearance] must already include that inset: `ListenUpScaffold` unions it with the system bars, and an
 * under-report lets the bar overlap a docked bottom bar (Save Changes).
 */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class NowPlayingBarFootprintTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a detail screen's bar reserves its own height plus the gesture inset it runs under`() {
        var measured = 0.dp
        composeRule.setContent {
            val density = LocalDensity.current
            MaterialTheme {
                NowPlayingBar(
                    state = loadedPlayerState(coverPath = "/nonexistent/cover.jpg"),
                    progress = { samplePlayerProgress },
                    isExpanded = false,
                    onTap = {},
                    onPrimary = {},
                    onSkipBack = {},
                    onKeepListening = {},
                    skipBackwardSec = 10,
                    bottomInset = WindowInsets(bottom = 24.dp),
                    modifier = Modifier.onSizeChanged { measured = with(density) { it.height.toDp() } },
                )
            }
        }
        composeRule.waitForIdle()

        nowPlayingClearance(
            barVisible = true,
            latchedFootprint = latchFootprint(current = 0.dp, measured = measured, barVisible = true),
        ) shouldBe MiniBarHeight + 24.dp
    }
}
