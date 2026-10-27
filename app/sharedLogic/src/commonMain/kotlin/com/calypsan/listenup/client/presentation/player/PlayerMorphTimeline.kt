package com.calypsan.listenup.client.presentation.player

import kotlin.math.PI
import kotlin.math.sin

/** A stretch of morph progress, [start] to [end], inside which one element moves. */
data class MorphWindow(
    val start: Float,
    val end: Float,
) {
    /** How far through this window progress [p] is, clamped to 0..1. Pure. */
    fun of(p: Float): Float = ((p - start) / (end - start)).coerceIn(0f, 1f)
}

/** The easing an element follows inside its window. */
internal enum class MorphCurve {
    /** `1 − (1 − t)^3`: the canvas's `outC`, for everything that fades or slides. */
    EaseOutCubic,

    /** Straight in progress: the sheet's colour. */
    Linear,
    ;

    fun ease(t: Float): Float =
        when (this) {
            EaseOutCubic -> 1f - (1f - t) * (1f - t) * (1f - t)
            Linear -> t
        }
}

/**
 * The canvas `Spec` table as data: every element that is not one of the four flying parts (cover, title, play button,
 * progress line), with the window of progress it moves in. Android's layers and web's CSS read these same windows, so
 * neither can drift from the other or from the canvas.
 */
enum class MorphElement(
    /** The stretch of progress the element moves in. */
    val window: MorphWindow,
    internal val curve: MorphCurve = MorphCurve.EaseOutCubic,
) {
    /** "Navigation bar: slides down 80 (web 84), 0–0.4, ease-out cubic." */
    NavigationBar(MorphWindow(0f, 0.4f)),

    /** "Bar's second line: fades out, 0–0.25, ease-out cubic." */
    BarSecondLine(MorphWindow(0f, 0.25f)),

    /** "Bar's skip-back (Android): fades out, 0–0.2, ease-out cubic." */
    BarSkipBack(MorphWindow(0f, 0.2f)),

    /** "Sheet: colour bar tint → surface, 0–0.6, linear in progress." */
    SheetColour(MorphWindow(0f, 0.6f), MorphCurve.Linear),

    /** "Progress line → scrubber: thickness 0.3–1, ease-out." */
    ScrubberThickness(MorphWindow(0.3f, 1f)),

    /** "Scrubber thumb: fades in, 0.75–1, ease-out cubic." */
    ScrubberThumb(MorphWindow(0.75f, 1f)),

    /** "Header (Collapse, Cast, More): fades in, rides the sheet, 0.65–1, ease-out cubic." */
    Header(MorphWindow(0.65f, 1f)),

    /** "Chapter: fades in, rides the sheet, 0.55–0.9, ease-out cubic." */
    Chapter(MorphWindow(0.55f, 0.9f)),

    /** "Narrator: fades in, rides the sheet, 0.6–0.95, ease-out cubic." */
    Narrator(MorphWindow(0.6f, 0.95f)),

    /** "Times: fade in, ride the sheet, 0.6–0.95, ease-out cubic." */
    Times(MorphWindow(0.6f, 0.95f)),

    /** "Transport side buttons: fade in; scale 0.85 → 1, 0.55–0.9, ease-out cubic." */
    TransportSides(MorphWindow(0.55f, 0.9f)),

    /** "Speed · Sleep · Chapters: fade in, ride the sheet, 0.65–1, ease-out cubic." */
    Settings(MorphWindow(0.65f, 1f)),
}

/**
 * How far an arriving [element] has come in at progress [p], 0..1, on its curve. Under [MorphMode.Crossfade] every
 * element is at rest (1), because only the whole player's opacity moves. Pure.
 */
fun reveal(
    element: MorphElement,
    p: Float,
    mode: MorphMode = MorphMode.Morph,
): Float = if (mode == MorphMode.Crossfade) 1f else element.curve.ease(element.window.of(p))

/**
 * How much of a leaving [element] (the bar's second line, its skip-back) is still there at progress [p]: its window
 * read as how far it has gone. At rest (1) under [MorphMode.Crossfade]. Pure.
 */
fun fade(
    element: MorphElement,
    p: Float,
    mode: MorphMode = MorphMode.Morph,
): Float = if (mode == MorphMode.Crossfade) 1f else 1f - reveal(element, p)

/** The sheet's top corners as a share of their full radius: `sin(πp)`, 0 at both ends, full halfway. Pure. */
fun sheetCornerFraction(p: Float): Float = sin(PI.toFloat() * p.coerceIn(0f, 1f))

/** The whole player's opacity: always 1 while it morphs, and progress itself under the crossfade. Pure. */
fun playerOpacity(
    p: Float,
    mode: MorphMode,
): Float = if (mode == MorphMode.Crossfade) p.coerceIn(0f, 1f) else 1f

/** The transport's side buttons grow from 85 % across their window. Pure. */
fun sidesScale(p: Float): Float = 1f - (1f - SIDES_START_SCALE) * (1f - reveal(MorphElement.TransportSides, p))

private const val SIDES_START_SCALE = 0.85f

/** Whether the title has switched to Now Playing's weight (and, on web, its face): past halfway. Pure. */
fun titleIsLarge(p: Float): Boolean = p > PlayerMorph.HALFWAY
