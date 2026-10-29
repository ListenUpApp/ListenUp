package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.nowplaying.components.CastConnectedButton
import com.calypsan.listenup.client.features.nowplaying.components.PlayerTopBar
import com.calypsan.listenup.client.presentation.player.AudioSource
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

private const val CAST_SLOT = "cast-slot"

/**
 * P1, the status slot and P25: Collapse, the chip (only with news), Cast and the More menu from the shared builder. The wide header (P3,
 * `NP-AndroidTablet`) reads just "Now playing": the series is a link in the player column, so More leaves it out.
 */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class PlayerTopBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val picked = mutableListOf<PlayerMenuItem>()
    private var collapses = 0

    private var source by mutableStateOf<AudioSource>(AudioSource.StreamingTranscoded)
    private var authors by mutableStateOf(listOf(PlayerContributor("a1", "Brandon Sanderson", 14)))

    private fun bar(
        wide: Boolean = false,
        castSlot: @Composable () -> Unit = { Box(Modifier.size(48.dp).testTag(CAST_SLOT)) },
    ) {
        composeRule.setContent {
            MaterialTheme {
                PlayerTopBar(
                    state =
                        loadedPlayerState(
                            source = source,
                            series = listOf(PlayerSeriesRef("s1", "The Stormlight Archive", "1")),
                            firstPdfDocId = "doc-1",
                            boostDb = 6f,
                            authors = authors,
                        ),
                    onCollapse = { collapses++ },
                    onMenuItem = { picked += it },
                    wide = wide,
                    castSlot = castSlot,
                )
            }
        }
    }

    private fun statusNodes() = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes()

    private fun statusWords(): List<String> =
        statusNodes()
            .single()
            .config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text }

    @Test
    fun `Downloaded, Streaming and downloaded-offline say nothing, from a polite live region that is already there`() {
        bar()
        listOf(AudioSource.Downloaded, AudioSource.Streaming, AudioSource.DownloadedOffline).forEach { quiet ->
            source = quiet
            composeRule.waitForIdle()
            statusNodes().size shouldBe 1
            statusWords() shouldBe emptyList()
        }
        composeRule.onNodeWithContentDescription("Collapse player").assertExists()
        composeRule.onNodeWithContentDescription("More options").assertExists()
    }

    @Test
    fun `the live region stays and only its words change when the source flips, so the news is announced`() {
        source = AudioSource.Streaming
        bar()
        val region = statusNodes().single()
        region.config[SemanticsProperties.LiveRegion] shouldBe LiveRegionMode.Polite
        statusWords() shouldBe emptyList()

        source = AudioSource.Offline
        composeRule.waitForIdle()
        statusNodes().single().id shouldBe region.id
        statusWords() shouldBe listOf("Offline")
    }

    @Test
    fun `Offline shows between Collapse and More`() {
        source = AudioSource.Offline
        bar()
        val collapse = composeRule.onNodeWithContentDescription("Collapse player").getUnclippedBoundsInRoot()
        val offline = composeRule.onNodeWithText("Offline").getUnclippedBoundsInRoot()
        val cast = composeRule.onNodeWithTag(CAST_SLOT).getUnclippedBoundsInRoot()
        val more = composeRule.onNodeWithContentDescription("More options").getUnclippedBoundsInRoot()
        (collapse.right <= offline.left) shouldBe true
        (offline.right <= cast.left) shouldBe true
        (cast.right <= more.left) shouldBe true
    }

    @Test
    fun `casting shows no chip`() {
        source = AudioSource.Casting("Living Room")
        bar(castSlot = { CastConnectedButton(deviceName = "Living Room", onOpenRoutes = {}) })
        statusWords() shouldBe emptyList()
        composeRule.onNodeWithText("Playing on Living Room").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Cast. Playing on Living Room. Change output").assertExists()
    }

    @Test
    fun `Cast sits beside More`() {
        bar()
        val cast = composeRule.onNodeWithTag(CAST_SLOT).getUnclippedBoundsInRoot()
        val more = composeRule.onNodeWithContentDescription("More options").getUnclippedBoundsInRoot()
        (cast.right <= more.left) shouldBe true
        abs(((cast.top + cast.bottom) / 2 - (more.top + more.bottom) / 2).value) shouldBeLessThanOrEqualTo 1f
    }

    @Test
    fun `the wide header has Cast beside More too`() {
        bar(wide = true)
        val cast = composeRule.onNodeWithTag(CAST_SLOT).getUnclippedBoundsInRoot()
        val more = composeRule.onNodeWithContentDescription("More options").getUnclippedBoundsInRoot()
        (cast.right <= more.left) shouldBe true
    }

    @Test
    fun `collapse and the status`() {
        bar()
        composeRule.onNodeWithContentDescription("Collapse player").performClick()
        collapses shouldBe 1
        composeRule.onNodeWithText("Streaming · transcoded").assertExists()
    }

    @Test
    fun `More lists Go to author after Go to series, the boost with its value, and no narrator row`() {
        bar()
        composeRule.onNodeWithContentDescription("More options").performClick()
        val series = composeRule.onNodeWithText("Go to series").fetchSemanticsNode().boundsInRoot
        val author = composeRule.onNodeWithText("Go to author").fetchSemanticsNode().boundsInRoot
        (series.bottom <= author.top) shouldBe true
        composeRule.onNodeWithText("Go to book").assertExists()
        composeRule.onNodeWithText("Open PDF").assertExists()
        composeRule.onNodeWithText("Volume boost · +6 dB").assertExists()
        composeRule.onNodeWithText("Go to narrator").assertDoesNotExist()
        composeRule.onNodeWithText("Go to author").performClick()
        picked shouldBe listOf(PlayerMenuItem.GoToAuthor("a1"))
    }

    @Test
    fun `with several authors, Go to author… opens the author picker`() {
        authors = listOf(PlayerContributor("a1", "Brandon Sanderson", 14), PlayerContributor("a2", "Dan Wells", 6))
        bar()
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Go to author…").performClick()
        picked shouldBe listOf(PlayerMenuItem.ChooseAuthor)
    }

    @Test
    fun `the wide bar's More has no author row, as the byline shows the authors`() {
        bar(wide = true)
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Go to author").assertDoesNotExist()
    }

    @Test
    fun `the wide header reads Now playing, not the series, and keeps the status and Collapse`() {
        bar(wide = true)
        composeRule.onNodeWithText("Now playing").assertExists()
        composeRule.onNodeWithText("The Stormlight Archive").assertDoesNotExist()
        composeRule.onNodeWithText("Streaming · transcoded").assertExists()
        composeRule.onNodeWithContentDescription("Collapse player").performClick()
        collapses shouldBe 1
    }

    @Test
    fun `the wide header's More leaves Go to series out`() {
        bar(wide = true)
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Go to book").assertExists()
        composeRule.onNodeWithText("Go to series").assertDoesNotExist()
    }
}
