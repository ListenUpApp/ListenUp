package com.calypsan.listenup.client.campfire

// ── flameLickColor palette bands — ported from fire.jsx's `flameColor` ─────────────────────────
private const val COLOR_CHANNEL_MAX = 255f
private const val FLAME_GREEN_FLOOR = 20f
private const val FLAME_CORE_THRESHOLD = 0.28f
private const val FLAME_AMBER_THRESHOLD = 0.55f
private const val FLAME_ORANGE_THRESHOLD = 0.8f
private const val FLAME_CORE_RED = 255f
private const val FLAME_CORE_GREEN = 248f
private const val FLAME_CORE_BLUE = 205f
private const val FLAME_AMBER_GREEN_START = 178f
private const val FLAME_AMBER_GREEN_SLOPE = 180f
private const val FLAME_AMBER_BLUE = 60f
private const val FLAME_ORANGE_GREEN_START = 110f
private const val FLAME_ORANGE_GREEN_SLOPE = 220f
private const val FLAME_ORANGE_BLUE = 26f
private const val FLAME_RED_START = 224f
private const val FLAME_RED_SLOPE = 300f
private const val FLAME_RED_GREEN = 60f
private const val FLAME_RED_BLUE = 20f

// ── emberSparkColor cooling — ported from fire.jsx's ember gradient stop-0 color ────────────────
private const val SPARK_GREEN_START = 210f
private const val SPARK_GREEN_SLOPE = 90f
private const val SPARK_BLUE_START = 140f
private const val SPARK_BLUE_SLOPE = 90f

// ── flameRadiusScale growth/taper — ported from fire.jsx's per-particle `r` formula ─────────────
private const val FLAME_RADIUS_BASE = 0.5f
private const val FLAME_RADIUS_GROWTH = 1.4f
private const val FLAME_TAPER_THRESHOLD = 0.7f
private const val FLAME_TAPER_SCALE = 0.3f
private const val FLAME_TAPER_FLOOR = 0.1f

// ── flameAlphaEnvelope fade-in/fade-out — ported from fire.jsx's per-particle `a` formula ───────
private const val FLAME_FADE_IN_END = 0.15f
private const val FLAME_ALPHA_PEAK = 0.42f

// ── computeFireGeometry proportions — ported from fire.jsx's `baseGeom()` ───────────────────────
private const val FIRE_BASE_X_FRACTION = 0.5f
private const val FIRE_BASE_WIDTH_FRACTION = 0.28f

// ── glowValue breathing range — ported from fire.jsx's `gv` formula ─────────────────────────────
private const val GLOW_BASELINE = 0.6f
private const val GLOW_INTENSITY_WEIGHT = 0.5f
private const val GLOW_MIN = 0.35f
private const val GLOW_MAX = 1.15f

/**
 * Normalized RGBA output (each channel `0f..1f`) of [flameLickColor] / [emberSparkColor] — kept
 * platform-agnostic (no `androidx.compose.ui.graphics.Color`) so the fire palette math is
 * testable in `:sharedLogic` without a Compose dependency. `CampfireBackdrop` (`:sharedUI`)
 * converts this to a real `Color` at the draw call site.
 */
data class FireColor(
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float,
)

/**
 * A flame lick's color at normalized age [t] (`0f` = just spawned / white-gold core, `1f` = about
 * to extinguish / cooled ember-red), ported from the Campfire design reference's `flameColor`
 * (co-listening design spec's `fire.jsx`). [alpha] passes through unchanged so callers can layer
 * their own age-based fade on top of the color.
 */
fun flameLickColor(
    t: Float,
    alpha: Float,
): FireColor {
    val age = t.coerceIn(0f, 1f)
    val (r, g, b) =
        when {
            age < FLAME_CORE_THRESHOLD -> {
                Triple(FLAME_CORE_RED, FLAME_CORE_GREEN, FLAME_CORE_BLUE)
            }

            age < FLAME_AMBER_THRESHOLD -> {
                Triple(
                    FLAME_CORE_RED,
                    FLAME_AMBER_GREEN_START - (age - FLAME_CORE_THRESHOLD) * FLAME_AMBER_GREEN_SLOPE,
                    FLAME_AMBER_BLUE,
                )
            }

            age < FLAME_ORANGE_THRESHOLD -> {
                Triple(
                    FLAME_CORE_RED,
                    FLAME_ORANGE_GREEN_START - (age - FLAME_AMBER_THRESHOLD) * FLAME_ORANGE_GREEN_SLOPE,
                    FLAME_ORANGE_BLUE,
                )
            }

            else -> {
                Triple(
                    FLAME_RED_START - (age - FLAME_ORANGE_THRESHOLD) * FLAME_RED_SLOPE,
                    FLAME_RED_GREEN,
                    FLAME_RED_BLUE,
                )
            }
        }
    return FireColor(
        red = (r / COLOR_CHANNEL_MAX).coerceIn(0f, 1f),
        green = (maxOf(g, FLAME_GREEN_FLOOR) / COLOR_CHANNEL_MAX).coerceIn(0f, 1f),
        blue = (b / COLOR_CHANNEL_MAX).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

/**
 * A rising ember/spark's color at normalized age [t] — cools from pale gold toward orange as it
 * climbs, ported from `fire.jsx`'s ember gradient stop-0 color. [alpha] passes through unchanged.
 */
fun emberSparkColor(
    t: Float,
    alpha: Float,
): FireColor {
    val age = t.coerceIn(0f, 1f)
    return FireColor(
        red = 1f,
        green = ((SPARK_GREEN_START - age * SPARK_GREEN_SLOPE) / COLOR_CHANNEL_MAX).coerceIn(0f, 1f),
        blue = ((SPARK_BLUE_START - age * SPARK_BLUE_SLOPE) / COLOR_CHANNEL_MAX).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

/**
 * A flame lick's radius scale factor at normalized age [t]: it grows for most of its life, then
 * tapers sharply in its final 30% as it extinguishes. Ported from `fire.jsx`'s per-particle `r`
 * formula.
 */
fun flameRadiusScale(t: Float): Float {
    val age = t.coerceIn(0f, 1f)
    val growth = FLAME_RADIUS_BASE + age * FLAME_RADIUS_GROWTH
    val taper =
        if (age > FLAME_TAPER_THRESHOLD) {
            (1f - age) / FLAME_TAPER_SCALE + FLAME_TAPER_FLOOR
        } else {
            1f
        }
    return growth * taper
}

/**
 * A flame lick's opacity envelope at normalized age [t]: a quick fade-in over the first 15% of its
 * life, then a slow fade-out over the rest. Ported from `fire.jsx`'s per-particle `a` formula.
 */
fun flameAlphaEnvelope(t: Float): Float {
    val age = t.coerceIn(0f, 1f)
    val envelope =
        if (age < FLAME_FADE_IN_END) {
            age / FLAME_FADE_IN_END
        } else {
            1f - (age - FLAME_FADE_IN_END) / (1f - FLAME_FADE_IN_END)
        }
    return (envelope * FLAME_ALPHA_PEAK).coerceIn(0f, 1f)
}

/**
 * The fire's base position and width in pixels, derived from the backdrop's current canvas size —
 * the anchor both the particle engine (spawn positions) and the canvas draw (coal bed, logs)
 * build from, so the two stay pixel-identical by construction. Ported from `fire.jsx`'s
 * `baseGeom()`.
 *
 * @param maxBaseWidthPx The device-density-converted equivalent of `fire.jsx`'s `138`px cap.
 */
fun computeFireGeometry(
    widthPx: Float,
    heightPx: Float,
    baseYFraction: Float,
    maxBaseWidthPx: Float,
): FireGeometry =
    FireGeometry(
        baseX = widthPx * FIRE_BASE_X_FRACTION,
        baseY = heightPx * baseYFraction,
        baseWidth = minOf(widthPx * FIRE_BASE_WIDTH_FRACTION, maxBaseWidthPx),
    )

/** The fire's base position ([baseX], [baseY]) and half-spread [baseWidth] in pixels — see [computeFireGeometry]. */
data class FireGeometry(
    val baseX: Float,
    val baseY: Float,
    val baseWidth: Float,
)

/**
 * The vertical pixel offset to apply to a bottom-anchored, fixed-height ambient-glow layer so its
 * center lands on the fire's [FireGeometry.baseY] — the single anchor the coal bed, logs, and flame
 * spawn origin already share. Without this, an `Alignment.BottomCenter` layer of fixed height centers
 * itself relative to the *canvas* bottom edge, which drifts away from `baseY` as `baseYFraction`
 * departs from the canvas midpoint — producing a detached glow "orb" below the fire instead of one
 * light source radiating from it. The result is negative (shift up) whenever `baseY` sits above the
 * layer's natural resting center, which is the common case.
 */
fun glowLayerCenteringOffsetPx(
    baseY: Float,
    canvasHeightPx: Float,
    layerHeightPx: Float,
): Float = baseY + layerHeightPx / 2f - canvasHeightPx

/**
 * The fire's overall brightness pulse — the single flicker-derived value that the ambient glow
 * layers, coal bed, and log embers all breathe in sync to. Ported from `fire.jsx`'s `gv` formula
 * (`Math.max(0.35, Math.min(1.15, (flick + jitter) * (0.6 + intensity * 0.5)))`).
 */
fun glowValue(
    flick: Float,
    jitter: Float,
    intensity: Float,
): Float = ((flick + jitter) * (GLOW_BASELINE + intensity * GLOW_INTENSITY_WEIGHT)).coerceIn(GLOW_MIN, GLOW_MAX)
