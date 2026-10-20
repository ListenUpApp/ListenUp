package com.calypsan.listenup.web

import com.calypsan.listenup.client.domain.model.CoverTheme
import com.calypsan.listenup.client.domain.model.CoverThemeStyle
import com.calypsan.listenup.client.domain.model.CoverThemes
import com.calypsan.listenup.client.domain.repository.CoverThemeRepository
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.pow

/** The Way of Kings' colours as a hero receives them, light and dark. */
internal val KINGS_THEMES =
    CoverThemes(
        light =
            CoverTheme(
                washArgb = 0xFFE4E4E6.toInt(),
                trackArgb = 0xFFEDD5B0.toInt(),
                actionArgb = 0xFFCC2F2A.toInt(),
                onActionArgb = 0xFFFFFFFF.toInt(),
            ),
        dark =
            CoverTheme(
                washArgb = 0xFF2B2D33.toInt(),
                trackArgb = 0xFF4A3A22.toInt(),
                actionArgb = 0xFFCC2F2A.toInt(),
                onActionArgb = 0xFFFFFFFF.toInt(),
            ),
    )

/**
 * A cover colour source for specs: [known] answers `cached` (a revisit); otherwise `observe` waits on [live], which a
 * spec emits into to play a first visit's late arrival.
 */
internal class FakeCoverThemes(
    private val known: CoverThemes?,
    val live: MutableSharedFlow<CoverThemes?> = MutableSharedFlow(replay = 1),
) : CoverThemeRepository {
    override fun cached(
        bookId: BookId,
        coverHash: String?,
        style: CoverThemeStyle,
    ): CoverThemes? = known

    override fun observe(
        bookId: BookId,
        coverHash: String?,
        style: CoverThemeStyle,
    ): Flow<CoverThemes?> = known?.let { flowOf(it) } ?: live
}

/** WCAG contrast between two computed `rgb(...)` colours. */
internal fun computedContrast(
    foreground: String,
    background: String,
): Double {
    fun luminance(computed: String): Double {
        val (r, g, b) =
            Regex("""rgba?\(([^)]*)\)""")
                .find(computed)!!
                .groupValues[1]
                .split(",")
                .take(3)
                .map { channel ->
                    val c = channel.trim().toDouble() / 255
                    if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
                }
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    val a = luminance(foreground)
    val b = luminance(background)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
