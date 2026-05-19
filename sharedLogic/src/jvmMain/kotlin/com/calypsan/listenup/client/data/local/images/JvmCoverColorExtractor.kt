package com.calypsan.listenup.client.data.local.images

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

private const val SAMPLE_SIZE = 10_000
private const val NUM_COLORS = 5

/**
 * JVM desktop implementation of [CoverColorExtractor] using AWT.
 *
 * Implements a simplified color quantization algorithm to extract
 * dominant colors from cover images. Uses uniform sampling of pixels
 * and k-means-style clustering to find representative colors.
 *
 * Features:
 * - Pure JVM implementation using java.awt
 * - Samples up to 10,000 pixels for performance
 * - Returns dominant, dark muted, and vibrant colors
 * - Gracefully handles invalid images by returning null
 */
class JvmCoverColorExtractor : CoverColorExtractor {
    override suspend fun extractColors(imageBytes: ByteArray): ExtractedColors? =
        withContext(Dispatchers.IO) {
            try {
                val image =
                    ByteArrayInputStream(imageBytes).use { stream ->
                        ImageIO.read(stream)
                    } ?: return@withContext null

                val colors = sampleColors(image)
                if (colors.isEmpty()) return@withContext null

                val clusters = kMeansClusters(colors, NUM_COLORS)
                if (clusters.isEmpty()) return@withContext null

                // Sort clusters by size (most pixels = dominant)
                val sortedClusters = clusters.sortedByDescending { it.size }

                val dominant = sortedClusters.first().center
                val darkMuted = findDarkMuted(sortedClusters.map { it.center })
                val vibrant = findVibrant(sortedClusters.map { it.center })

                ExtractedColors(
                    dominant = colorToArgb(dominant),
                    darkMuted = colorToArgb(darkMuted),
                    vibrant = colorToArgb(vibrant),
                )
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Sample colors from the image using uniform grid sampling.
     */
    private fun sampleColors(image: BufferedImage): List<FloatArray> {
        val width = image.width
        val height = image.height
        val totalPixels = width * height

        val step = maxOf(1, totalPixels / SAMPLE_SIZE)
        val colors = mutableListOf<FloatArray>()

        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (index % step == 0) {
                    val rgb = image.getRGB(x, y)
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF

                    // Skip very dark or very light pixels
                    val brightness = (r + g + b) / 3
                    if (brightness in 20..235) {
                        colors.add(floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat()))
                    }
                }
                index++
            }
        }

        return colors
    }

    /**
     * Simple k-means clustering to find representative colors.
     */
    private fun kMeansClusters(
        colors: List<FloatArray>,
        k: Int,
    ): List<Cluster> {
        if (colors.size < k) return colors.map { Cluster(it, 1) }

        // Initialize centroids using k-means++ style selection
        val centroids = mutableListOf<FloatArray>()
        centroids.add(colors.random())

        repeat(k - 1) {
            val distances =
                colors.map { color ->
                    centroids.minOf { centroid -> distance(color, centroid) }
                }
            val maxIdx = distances.indices.maxByOrNull { distances[it] } ?: 0
            centroids.add(colors[maxIdx].copyOf())
        }

        // Run k-means iterations
        repeat(10) {
            val clusters = Array(k) { mutableListOf<FloatArray>() }

            // Assign colors to nearest centroid
            for (color in colors) {
                val nearestIdx = centroids.indices.minByOrNull { distance(color, centroids[it]) } ?: 0
                clusters[nearestIdx].add(color)
            }

            // Update centroids
            for (i in centroids.indices) {
                if (clusters[i].isNotEmpty()) {
                    centroids[i] =
                        clusters[i]
                            .reduce { acc, arr ->
                                floatArrayOf(acc[0] + arr[0], acc[1] + arr[1], acc[2] + arr[2])
                            }.let { sum ->
                                val size = clusters[i].size
                                floatArrayOf(sum[0] / size, sum[1] / size, sum[2] / size)
                            }
                }
            }
        }

        // Build final clusters
        val finalClusters = Array(k) { mutableListOf<FloatArray>() }
        for (color in colors) {
            val nearestIdx = centroids.indices.minByOrNull { distance(color, centroids[it]) } ?: 0
            finalClusters[nearestIdx].add(color)
        }

        return centroids.indices
            .filter { finalClusters[it].isNotEmpty() }
            .map { Cluster(centroids[it], finalClusters[it].size) }
    }

    private fun distance(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        val dr = a[0] - b[0]
        val dg = a[1] - b[1]
        val db = a[2] - b[2]
        return dr * dr + dg * dg + db * db
    }

    /**
     * Find a dark, muted color suitable for backgrounds.
     */
    private fun findDarkMuted(colors: List<FloatArray>): FloatArray {
        // Convert to HSB and find darkest, least saturated
        return colors.minByOrNull { color ->
            val hsb = Color.RGBtoHSB(color[0].toInt(), color[1].toInt(), color[2].toInt(), null)
            val saturation = hsb[1]
            val brightness = hsb[2]
            // Prefer low brightness and low saturation
            brightness + saturation * 0.5f
        } ?: colors.first()
    }

    /**
     * Find a vibrant accent color.
     */
    private fun findVibrant(colors: List<FloatArray>): FloatArray {
        // Convert to HSB and find most saturated with medium brightness
        return colors.maxByOrNull { color ->
            val hsb = Color.RGBtoHSB(color[0].toInt(), color[1].toInt(), color[2].toInt(), null)
            val saturation = hsb[1]
            val brightness = hsb[2]
            // Prefer high saturation and medium brightness
            saturation * (1 - kotlin.math.abs(brightness - 0.5f) * 2)
        } ?: colors.first()
    }

    private fun colorToArgb(color: FloatArray): Int {
        val r = color[0].toInt().coerceIn(0, 255)
        val g = color[1].toInt().coerceIn(0, 255)
        val b = color[2].toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private data class Cluster(
        val center: FloatArray,
        val size: Int,
    )
}
