package com.calypsan.listenup.client.core.color

import com.materialkolor.quantize.QuantizerCelebi
import kotlin.math.abs

/** One colour the quantizer found in a cover, and how many sampled pixels it stands for. */
internal data class CoverCluster(
    val argb: Int,
    val population: Int,
)

/**
 * A cover's colours (Book Detail spec §2.3 step 1): every quantized cluster, most populous first, and the palette roles
 * the theme rule reads. A role is null when no cluster fits it; no cluster fills two roles.
 */
internal data class CoverSwatches(
    val clusters: List<CoverCluster>,
    val lightVibrant: CoverCluster?,
    val vibrant: CoverCluster?,
    val darkVibrant: CoverCluster?,
    val lightMuted: CoverCluster?,
    val muted: CoverCluster?,
    val darkMuted: CoverCluster?,
) {
    val totalPopulation: Int get() = clusters.sumOf { it.population }
}

/**
 * The swatches of a downsampled cover: [argb] row-major, [width] × [height]. Pixels under half alpha are ignored, so a
 * transparent cover has none. One algorithm on every platform (spec §4.4): MCU's Celebi quantizer, then
 * androidx.palette's role targets and weights (not androidx.palette itself, which only Android has).
 */
internal fun extractSwatches(
    argb: IntArray,
    width: Int,
    height: Int,
): CoverSwatches? {
    require(argb.size == width * height) { "expected ${width * height} pixels, got ${argb.size}" }
    val opaque = argb.filter { it ushr ALPHA_SHIFT >= MIN_OPAQUE_ALPHA }.map { it or OPAQUE }.toIntArray()
    if (opaque.isEmpty()) return null
    val clusters =
        quantize(opaque)
            .map { (color, count) -> CoverCluster(color, count) }
            .sortedWith(compareByDescending<CoverCluster> { it.population }.thenBy { it.argb })
    val roles = assignRoles(clusters)
    return CoverSwatches(
        clusters = clusters,
        lightVibrant = roles[SwatchRole.LIGHT_VIBRANT],
        vibrant = roles[SwatchRole.VIBRANT],
        darkVibrant = roles[SwatchRole.DARK_VIBRANT],
        lightMuted = roles[SwatchRole.LIGHT_MUTED],
        muted = roles[SwatchRole.MUTED],
        darkMuted = roles[SwatchRole.DARK_MUTED],
    )
}

/**
 * Colour → pixel count. A cover with no more distinct colours than the budget is its own exact palette. Celebi's
 * Wsmeans pass starts from a random assignment and only moves a colour for a gain above 3 ΔE, so on a handful of flat
 * colours (a generated placeholder cover) it can merge two of them into one muddy cluster; quantizing is only needed
 * when there is something to reduce.
 */
private fun quantize(opaque: IntArray): Map<Int, Int> {
    val exact = opaque.toList().groupingBy { it }.eachCount()
    return if (exact.size <= MAX_CLUSTERS) exact else QuantizerCelebi.quantize(opaque, MAX_CLUSTERS)
}

/** androidx.palette's default targets: lightness and saturation windows and the value each role aims for (HSL). */
private enum class SwatchRole(
    val minLightness: Float,
    val targetLightness: Float,
    val maxLightness: Float,
    val minSaturation: Float,
    val targetSaturation: Float,
    val maxSaturation: Float,
) {
    LIGHT_VIBRANT(0.55f, 0.74f, 1f, 0.35f, 1f, 1f),
    VIBRANT(0.3f, 0.5f, 0.7f, 0.35f, 1f, 1f),
    DARK_VIBRANT(0f, 0.26f, 0.45f, 0.35f, 1f, 1f),
    LIGHT_MUTED(0.55f, 0.74f, 1f, 0f, 0.3f, 0.4f),
    MUTED(0.3f, 0.5f, 0.7f, 0f, 0.3f, 0.4f),
    DARK_MUTED(0f, 0.26f, 0.45f, 0f, 0.3f, 0.4f),
}

/** Fills the roles in palette's order; each takes its best-scoring cluster not already taken. */
private fun assignRoles(clusters: List<CoverCluster>): Map<SwatchRole, CoverCluster> {
    val maxPopulation = clusters.maxOf { it.population }.toFloat()
    val taken = mutableSetOf<CoverCluster>()
    return buildMap {
        SwatchRole.entries.forEach { role ->
            val best =
                clusters
                    .filterNot { it in taken }
                    .mapNotNull { cluster -> role.score(cluster, maxPopulation)?.let { cluster to it } }
                    .maxByOrNull { it.second }
                    ?.first
            if (best != null) {
                put(role, best)
                taken += best
            }
        }
    }
}

private fun SwatchRole.score(
    cluster: CoverCluster,
    maxPopulation: Float,
): Float? {
    val (saturation, lightness) = saturationAndLightness(cluster.argb)
    if (saturation !in minSaturation..maxSaturation || lightness !in minLightness..maxLightness) return null
    return SATURATION_WEIGHT * (1f - abs(saturation - targetSaturation)) +
        LIGHTNESS_WEIGHT * (1f - abs(lightness - targetLightness)) +
        POPULATION_WEIGHT * (cluster.population / maxPopulation)
}

/** HSL saturation and lightness of [argb], each 0..1. */
private fun saturationAndLightness(argb: Int): Pair<Float, Float> {
    val red = ((argb shr 16) and CHANNEL) / CHANNEL_F
    val green = ((argb shr 8) and CHANNEL) / CHANNEL_F
    val blue = (argb and CHANNEL) / CHANNEL_F
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lightness = (max + min) / 2f
    val saturation = if (max == min) 0f else (max - min) / (1f - abs(2f * lightness - 1f))
    return saturation to lightness
}

private const val ALPHA_SHIFT = 24
private const val MIN_OPAQUE_ALPHA = 128
private const val OPAQUE = 0xFF000000.toInt()
private const val MAX_CLUSTERS = 16
private const val CHANNEL = 0xFF
private const val CHANNEL_F = 255f
private const val SATURATION_WEIGHT = 0.24f
private const val LIGHTNESS_WEIGHT = 0.52f
private const val POPULATION_WEIGHT = 0.24f
