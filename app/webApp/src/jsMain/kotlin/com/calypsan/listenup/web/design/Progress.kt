package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLDivElement
import kotlin.math.roundToInt

/**
 * How much of something is done, drawn as a filled track.
 *
 * There were seven of these — the library card's rail, two on Home, the bulk editor's, the
 * organizer's, the uploader's, two cover-tile overlays — plus `ProgressLine`, each its own track and
 * fill, most animating `width` (a layout on every frame) and most silent to a screen reader. This is
 * all of them, in three [look]s, because a 3px rail under a card and a labelled bar in a dialog are
 * genuinely different:
 * - [ProgressLook.Rail]: 3px, under a card's title — listening progress at a glance;
 * - [ProgressLook.Bar]: 6px, the working bar — a scan, an upload, a book's own progress;
 * - [ProgressLook.Overlay]: 4px, pinned along the bottom edge of a cover tile, with no track.
 *
 * [value] is a fraction, clamped rather than trusted: a stored position slightly past a re-encoded
 * file's end is real, and an unclamped fill overflows its track. `null` is indeterminate — work of
 * unknown size, drawn as a moving shimmer rather than a bar pinned at zero that reads as hung.
 *
 * The fill is `transform: scaleX`, composited, never a `width` that lays the page out again.
 *
 * It is a `role=progressbar` with its value, named by [label]. [caption] is the visible words beside
 * a bar ("49% · 9h 18m left"). [decorative] is for a bar that restates a number printed beside it: it
 * is hidden from assistive technology, which has already read the number.
 */
@Composable
fun ProgressBar(
    value: Float?,
    label: String? = null,
    look: ProgressLook = ProgressLook.Bar,
    caption: String? = null,
    decorative: Boolean = false,
    attrs: (AttrsScope<HTMLDivElement>.() -> Unit)? = null,
) {
    val fraction = value?.coerceIn(0f, 1f)
    val track: @Composable () -> Unit = {
        Div(attrs = {
            classes("progress", "progress-" + look.name.lowercase())
            if (fraction == null) classes("is-indeterminate")
            if (decorative) {
                attr("aria-hidden", "true")
            } else {
                attr("role", "progressbar")
                attr("aria-valuemin", "0")
                attr("aria-valuemax", PERCENT_MAX.toString())
                fraction?.let { attr("aria-valuenow", percentOf(it).toString()) }
                label?.let { attr("aria-label", it) }
            }
            attrs?.invoke(this)
        }) {
            Div(attrs = {
                classes("progress-fill")
                fraction?.let { style { property("transform", "scaleX($it)") } }
            })
        }
    }
    if (caption == null) {
        track()
    } else {
        Div(attrs = { classes("progress-line") }) {
            track()
            Span(attrs = { classes("progress-caption", "mono") }) { Text(caption) }
        }
    }
}

/** The three shapes of progress — see [ProgressBar]. */
enum class ProgressLook { Rail, Bar, Overlay }

/** A fraction as the whole percent a caption and `aria-valuenow` both print. */
fun percentOf(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * PERCENT_MAX).roundToInt()

private const val PERCENT_MAX = 100
