package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.presentation.player.MorphElement
import com.calypsan.listenup.client.presentation.player.SpringCurve
import com.calypsan.listenup.client.presentation.player.SpringParameters
import org.w3c.dom.HTMLElement

/*
 * The player morph's numbers, handed to CSS (canvas `GestureWeb`, HOW IT'S DRAWN).
 *
 * The shared timeline (`PlayerMorphTimeline`) is the one source of every element's window; this file writes those
 * windows into custom properties once, on the shell, and from then on a frame writes exactly one number:
 * [MORPH_PROGRESS]. `05-patterns.css` (PLAYER MORPH) turns that number into transforms and opacities, so nothing
 * recomposes and nothing lays out while the bar grows into Now Playing.
 */

/** Progress, 0 at the bar and 1 at Now Playing: the one value written per frame. Registered as a `<number>`. */
internal const val MORPH_PROGRESS = "--mp-p"

/** The distance from the bar's top edge to the top of the window, in px: the sheet's full travel. */
internal const val MORPH_TRAVEL = "--mp-travel"

/** The custom-property stem each element's window is written under: `--mp-<stem>-a` and `--mp-<stem>-b`. */
internal fun MorphElement.cssStem(): String =
    when (this) {
        MorphElement.NavigationBar -> "nav"
        MorphElement.BarSecondLine -> "sub"
        MorphElement.BarSkipBack -> "skip"
        MorphElement.SheetColour -> "tint"
        MorphElement.ScrubberThickness -> "thick"
        MorphElement.ScrubberThumb -> "thumb"
        MorphElement.Header -> "head"
        MorphElement.Chapter -> "chapter"
        MorphElement.Narrator -> "narrator"
        MorphElement.Times -> "times"
        MorphElement.TransportSides -> "sides"
        MorphElement.Settings -> "settings"
    }

/** Writes every element's window onto [root] (the shell), once: CSS reads them for as long as the shell lives. */
internal fun writeMorphTimeline(root: HTMLElement) {
    MorphElement.entries.forEach { element ->
        val stem = element.cssStem()
        root.style.setProperty("--mp-$stem-a", element.window.start.toString())
        root.style.setProperty("--mp-$stem-b", element.window.end.toString())
    }
}

/**
 * A spring as a CSS `linear()` easing and the duration it needs, sampled every [SAMPLE_STEP_MS] by the shared
 * [SpringCurve] so web plays the same spring Android's animator does. [initialVelocity] is in fractions of the
 * distance per second: a finger's release carried into the motion.
 */
internal class SpringEasing(
    val easing: String,
    val durationMs: Int,
) {
    companion object {
        /** [spring] from rest, or launched at [initialVelocity]. */
        fun of(
            spring: SpringParameters,
            initialVelocity: Float = 0f,
        ): SpringEasing {
            val samples = SpringCurve.sample(spring, initialVelocity, SAMPLE_STEP_MS)
            return SpringEasing(
                easing = samples.joinToString(prefix = "linear(", postfix = ")") { it.toString() },
                durationMs = (samples.size - 1) * SAMPLE_STEP_MS,
            )
        }
    }
}

/** One sample a frame at 60 Hz: fine enough that the curve between samples is indistinguishable from the spring. */
private const val SAMPLE_STEP_MS = 16
