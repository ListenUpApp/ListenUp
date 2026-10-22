package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.calypsan.listenup.client.design.theme.DarkExtendedColors
import com.calypsan.listenup.client.design.theme.LightExtendedColors
import com.calypsan.listenup.client.design.theme.ListenUpExtendedColors
import com.calypsan.listenup.client.design.theme.LocalExtendedColors
import com.calypsan.listenup.client.features.bookdetail.components.HeldForReviewSection
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Held is a fixed semantic colour (spec §3.2.3): amber whatever the wallpaper. Dynamic colour hands Material's tertiary
 * role to the wallpaper (pink, green, blue…), so the held panel must draw from the fixed held roles, never tertiary.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2400dp")
class HeldAmberTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Tertiary roles a wallpaper might hand out: pink, green and blue seeds, light and dark. */
    private val wallpapers =
        listOf(
            false to lightColorScheme(tertiary = Color(0xFF8E4A63), tertiaryContainer = Color(0xFFFFD9E4)),
            false to lightColorScheme(tertiary = Color(0xFF3B6939), tertiaryContainer = Color(0xFFBCF0B4)),
            false to lightColorScheme(tertiary = Color(0xFF345CA8), tertiaryContainer = Color(0xFFD9E2FF)),
            true to darkColorScheme(tertiary = Color(0xFFFFB0CB), tertiaryContainer = Color(0xFF6F334B)),
            true to darkColorScheme(tertiary = Color(0xFFA1D39A), tertiaryContainer = Color(0xFF235024)),
        )

    @Test
    fun `the held panel and its Release stay amber under every wallpaper`() {
        composeRule.setContent {
            Column {
                wallpapers.forEach { (dark, scheme) ->
                    CompositionLocalProvider(LocalExtendedColors provides extended(dark)) {
                        MaterialTheme(colorScheme = scheme) {
                            HeldForReviewSection(
                                isReleasing = false,
                                onReleaseClick = {},
                                onEditClick = {},
                                onMatchClick = {},
                                onEditChaptersClick = {},
                                modifier = Modifier.testTag("heldPanel"),
                            )
                        }
                    }
                }
            }
        }
        val panels = composeRule.onAllNodesWithTag("heldPanel")
        val releases = composeRule.onAllNodesWithTag("heldRelease")
        wallpapers.forEachIndexed { index, (dark, _) ->
            val held = extended(dark)
            val panel = panels[index].captureToImage().toPixelMap().let { it[it.width / 2, 6] }
            val release = releases[index].captureToImage().toPixelMap().let { it[it.width / 8, it.height / 2] }
            withClue("wallpaper $index panel") { panel.isNear(held.heldContainer) shouldBe true }
            withClue("wallpaper $index Release") { release.isNear(held.held) shouldBe true }
        }
    }

    private fun extended(dark: Boolean): ListenUpExtendedColors = if (dark) DarkExtendedColors else LightExtendedColors

    /** The button's surface tint lifts its fill a shade, so compare within a small distance. */
    private fun Color.isNear(other: Color): Boolean =
        maxOf(abs(red - other.red), abs(green - other.green), abs(blue - other.blue)) < TOLERANCE

    private companion object {
        const val TOLERANCE = 0.08f
    }
}
