package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.core.image.frameLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.web.attributes.alt
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Img
import org.w3c.dom.HTMLImageElement
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A photo drawn the way its [frame] says, clipped to its box. The box is the wrapping `div.framed`,
 * which takes its size and shape from [classes], so every caller keeps its own circle.
 *
 * Positioned in **percentages**: `frameLayout` is linear in the viewport, so a layout computed for a
 * 1×1 box, times 100%, is right at every size. No ResizeObserver, and nothing to redo when the box
 * resizes. Until the image loads its natural size is unknown, so it stays a centre crop
 * (`object-fit: cover`) and nothing jumps unless a frame actually moves it.
 *
 * [onError] hears a failed load. For a contributor or a user that is routine — most have no photo,
 * and the endpoint 404s — so callers swap in the monogram rather than reporting anything.
 */
@Composable
fun FramedImg(
    src: String,
    alt: String,
    frame: ImageFrame?,
    classes: List<String> = emptyList(),
    onError: (() -> Unit)? = null,
) {
    var natural by remember(src) { mutableStateOf<Pair<Double, Double>?>(null) }
    Div(attrs = { classes(listOf(FRAMED) + classes) }) {
        Img(src = src, attrs = {
            classes(FRAMED_IMG)
            alt(alt)
            attr("decoding", "async")
            val size = natural
            if (size == null) {
                classes(PENDING)
            } else {
                val layout = frameLayout(size.first, size.second, 1.0, 1.0, frame)
                style {
                    property("width", formatPercent(layout.width))
                    property("height", formatPercent(layout.height))
                    property("left", formatPercent(layout.offsetX))
                    property("top", formatPercent(layout.offsetY))
                }
            }
            addEventListener("load") { event ->
                val img = event.target as HTMLImageElement
                if (img.naturalWidth > 0 && img.naturalHeight > 0) {
                    natural = img.naturalWidth.toDouble() to img.naturalHeight.toDouble()
                }
            }
            addEventListener("error") { onError?.invoke() }
        })
    }
}

/**
 * [fraction] of the box as a CSS percentage with four decimals, built by hand: a JS double prints
 * `1.0` as `"1"`, and a formatter that only works for the fractional cases is a bug waiting for a
 * whole number.
 */
internal fun formatPercent(fraction: Double): String {
    val tenThousandths = (fraction * PERCENT_SCALE).roundToLong()
    val sign = if (tenThousandths < 0) "-" else ""
    val magnitude = abs(tenThousandths)
    val whole = magnitude / DECIMAL_SCALE
    val decimals = (magnitude % DECIMAL_SCALE).toString().padStart(DECIMALS, '0')
    return "$sign$whole.$decimals%"
}

/**
 * Each user's avatar frame, observed from their `public_profiles` row. Provided once at the shell
 * (`WebAppRoot`) from the graph, so every [UserAvatar] is framed without its call sites threading a
 * frame through. The default is "no frame", today's centre crop, for specs and the auth screens.
 */
val LocalAvatarFrames = compositionLocalOf<(userId: String) -> Flow<ImageFrame?>> { { flowOf(null) } }

private const val FRAMED = "framed"

private const val FRAMED_IMG = "framed-img"

private const val PENDING = "is-pending"

private const val DECIMALS = 4

private const val DECIMAL_SCALE = 10_000L

/** 100 for a percentage, times [DECIMAL_SCALE] for its four decimals. */
private const val PERCENT_SCALE = 1_000_000.0
