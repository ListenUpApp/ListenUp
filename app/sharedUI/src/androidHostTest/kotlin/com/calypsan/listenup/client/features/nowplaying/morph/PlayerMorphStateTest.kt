package com.calypsan.listenup.client.features.nowplaying.morph

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.calypsan.listenup.client.design.motion.LocalReduceMotion
import com.calypsan.listenup.client.features.nowplaying.EntryMotion
import com.calypsan.listenup.client.presentation.player.MorphEnd
import com.calypsan.listenup.client.presentation.player.PlayerSheetState
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** One morph progress at the host: it follows the shared destination, yields to a drag, and writes back once. */
@RunWith(RobolectricTestRunner::class)
class PlayerMorphStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sheet = PlayerSheetState()
    private lateinit var morph: PlayerMorphState
    private val destinations = mutableListOf<Boolean>()

    private fun host(reduceMotion: Boolean = false) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                MaterialTheme {
                    val isExpanded by sheet.expanded.collectAsState()
                    morph =
                        rememberPlayerMorph(
                            isExpanded = { sheet.expanded.value },
                            onExpand = sheet::expand,
                            onCollapse = sheet::collapse,
                        )
                    PlayerMorphSync(morph = morph, isExpanded = isExpanded, entryMotion = EntryMotion.Morph)
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `expand() from outside animates progress to 1 on the slow spatial spring`() {
        host()
        sheet.expand()
        composeRule.mainClock.advanceTimeBy(100)
        morph.progress shouldBeGreaterThan 0f
        morph.progress shouldBeLessThan 1f
        composeRule.mainClock.advanceTimeBy(1_500)
        morph.progress shouldBe 1f
        morph.isInFlight shouldBe false
    }

    @Test
    fun `a drag owns progress, so an external change during a drag waits for the release`() {
        host()
        morph.snapDrag(0.3f)
        sheet.expand()
        composeRule.mainClock.advanceTimeBy(600)
        morph.progress shouldBe 0.3f
    }

    @Test
    fun `settling at 0 or 1 writes the destination once`() {
        host()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val recording = scope.launch { sheet.expanded.toList(destinations) }
        morph.snapDrag(0.7f)
        morph.release(MorphEnd.NowPlaying, velocity = 0f)
        composeRule.mainClock.advanceTimeBy(1_500)
        morph.progress shouldBe 1f
        destinations shouldBe listOf(false, true)
        recording.cancel()
    }

    @Test
    fun `under reduce motion a tap snaps`() {
        host(reduceMotion = true)
        sheet.expand()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        morph.progress shouldBe 1f
    }
}
