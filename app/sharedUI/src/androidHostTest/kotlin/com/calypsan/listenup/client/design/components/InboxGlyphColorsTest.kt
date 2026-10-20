package com.calypsan.listenup.client.design.components

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import io.kotest.matchers.shouldNotBe
import org.junit.Test

/** Spec a6: the inbox strip is amber, so its glyph tile must not be the same amber, or it disappears. */
class InboxGlyphColorsTest {
    @Test
    fun `on the amber strip the tile is a different colour from the strip`() {
        listOf(lightColorScheme(), darkColorScheme()).forEach { scheme ->
            val colors = inboxGlyphColors(scheme, onAmber = true)
            colors.tile shouldNotBe scheme.tertiaryContainer
            colors.glyph shouldNotBe colors.tile
        }
    }
}
