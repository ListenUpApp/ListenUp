package com.calypsan.listenup.client.design.components

import com.calypsan.listenup.client.design.theme.DarkExtendedColors
import com.calypsan.listenup.client.design.theme.LightExtendedColors
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

/** Spec a6: the inbox strip is amber, so its glyph tile must not be the same amber, or it disappears. */
class InboxGlyphColorsTest {
    @Test
    fun `on the amber strip the tile is a different colour from the strip`() {
        listOf(LightExtendedColors, DarkExtendedColors).forEach { held ->
            val colors = inboxGlyphColors(held, onAmber = true)
            colors.tile shouldNotBe held.heldContainer
            colors.glyph shouldNotBe colors.tile
        }
    }

    @Test
    fun `the tile is the fixed held amber, which no wallpaper changes`() {
        inboxGlyphColors(LightExtendedColors, onAmber = false).tile shouldBe LightExtendedColors.heldContainer
        inboxGlyphColors(LightExtendedColors, onAmber = true).tile shouldBe LightExtendedColors.held
    }
}
