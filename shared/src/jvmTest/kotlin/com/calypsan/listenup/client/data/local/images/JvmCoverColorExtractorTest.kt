package com.calypsan.listenup.client.data.local.images

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Integration tests for JvmCoverColorExtractor.
 *
 * Tests color extraction using programmatically generated test images
 * to verify the k-means clustering and color selection logic.
 */
class JvmCoverColorExtractorTest :
    FunSpec({
        val extractor = JvmCoverColorExtractor()

        fun createSolidColorImage(
            color: Color,
            width: Int = 100,
            height: Int = 100,
            format: String = "PNG",
        ): ByteArray {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = color
            graphics.fillRect(0, 0, width, height)
            graphics.dispose()

            return ByteArrayOutputStream().use { baos ->
                ImageIO.write(image, format, baos)
                baos.toByteArray()
            }
        }

        fun createTwoToneImage(
            leftColor: Color,
            rightColor: Color,
            width: Int = 100,
            height: Int = 100,
        ): ByteArray {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()

            // Left half
            graphics.color = leftColor
            graphics.fillRect(0, 0, width / 2, height)

            // Right half
            graphics.color = rightColor
            graphics.fillRect(width / 2, 0, width / 2, height)

            graphics.dispose()

            return ByteArrayOutputStream().use { baos ->
                ImageIO.write(image, "PNG", baos)
                baos.toByteArray()
            }
        }

        test("extracts dominant color from solid red image") {
            runTest {
                // Given - a solid red image
                val imageBytes = createSolidColorImage(Color.RED)

                // When
                val colors = extractor.extractColors(imageBytes)

                // Then
                colors.shouldNotBeNull()
                val dominant = Color(colors.dominant)
                // Should be close to red (allowing some variance from algorithm)
                (dominant.red > 200) shouldBe true
                (dominant.green < 50) shouldBe true
                (dominant.blue < 50) shouldBe true
            }
        }

        test("extracts dominant color from solid blue image") {
            runTest {
                // Given - a solid blue image
                val imageBytes = createSolidColorImage(Color.BLUE)

                // When
                val colors = extractor.extractColors(imageBytes)

                // Then
                colors.shouldNotBeNull()
                val dominant = Color(colors.dominant)
                (dominant.blue > 200) shouldBe true
                (dominant.red < 50) shouldBe true
                (dominant.green < 50) shouldBe true
            }
        }

        test("extracts colors from two-tone image") {
            runTest {
                // Given - image split vertically: left half red, right half blue
                val imageBytes = createTwoToneImage(Color.RED, Color.BLUE)

                // When
                val colors = extractor.extractColors(imageBytes)

                // Then
                colors.shouldNotBeNull()
                // Dominant should be either red or blue (whichever cluster is larger)
                val dominant = Color(colors.dominant)
                val isRedish = dominant.red > 150 && dominant.blue < 100
                val isBlueish = dominant.blue > 150 && dominant.red < 100
                (isRedish || isBlueish) shouldBe true
            }
        }

        test("returns null for empty byte array") {
            runTest {
                // When
                val colors = extractor.extractColors(ByteArray(0))

                // Then
                colors shouldBe null
            }
        }

        test("returns null for invalid image data") {
            runTest {
                // Given - random garbage bytes
                val garbageBytes = "not an image at all".toByteArray()

                // When
                val colors = extractor.extractColors(garbageBytes)

                // Then
                colors shouldBe null
            }
        }

        test("handles very small image") {
            runTest {
                // Given - 1x1 pixel image
                val imageBytes = createSolidColorImage(Color.GREEN, width = 1, height = 1)

                // When
                val colors = extractor.extractColors(imageBytes)

                // Then - might be null (too few pixels) or extract the color
                // Either behavior is acceptable for edge case
                if (colors != null) {
                    val dominant = Color(colors.dominant)
                    (dominant.green > 100) shouldBe true
                }
            }
        }

        test("handles large image efficiently") {
            runTest {
                // Given - large image (1000x1000 = 1M pixels)
                val imageBytes = createSolidColorImage(Color.ORANGE, width = 1000, height = 1000)

                // When - should complete in reasonable time due to sampling
                val startTime = System.currentTimeMillis()
                val colors = extractor.extractColors(imageBytes)
                val elapsed = System.currentTimeMillis() - startTime

                // Then
                colors.shouldNotBeNull()
                (elapsed < 5000) shouldBe true
            }
        }

        test("vibrant color has high saturation") {
            runTest {
                // Given - image dominated by saturated color (75% red, 25% gray)
                // Asymmetric split ensures k-means reliably produces a saturated cluster
                val image = java.awt.image.BufferedImage(200, 200, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                graphics.color = Color(255, 0, 0)
                graphics.fillRect(0, 0, 150, 200)
                graphics.color = Color(128, 128, 128)
                graphics.fillRect(150, 0, 50, 200)
                graphics.dispose()
                val imageBytes =
                    java.io.ByteArrayOutputStream().use { baos ->
                        javax.imageio.ImageIO.write(image, "PNG", baos)
                        baos.toByteArray()
                    }

                // When
                val colors = extractor.extractColors(imageBytes)

                // Then - vibrant should pick the saturated red cluster
                colors.shouldNotBeNull()
                val vibrant = Color(colors.vibrant)
                val hsb = Color.RGBtoHSB(vibrant.red, vibrant.green, vibrant.blue, null)
                val saturation = hsb[1]
                (saturation > 0.1f) shouldBe true
            }
        }

        test("dark muted color has low brightness") {
            runTest {
                // Given - image with dark and light colors
                val imageBytes =
                    createTwoToneImage(
                        Color(50, 30, 30), // Dark brownish
                        Color(255, 255, 200), // Light cream
                    )

                // When
                val colors = extractor.extractColors(imageBytes)

                // Then
                colors.shouldNotBeNull()
                val darkMuted = Color(colors.darkMuted)
                val hsb = Color.RGBtoHSB(darkMuted.red, darkMuted.green, darkMuted.blue, null)
                val brightness = hsb[2]
                (brightness < 0.7f) shouldBe true
            }
        }

        test("colors are valid ARGB integers") {
            runTest {
                // Given
                val imageBytes = createSolidColorImage(Color.CYAN)

                // When
                val colors = extractor.extractColors(imageBytes)

                // Then
                colors.shouldNotBeNull()

                // All colors should have full alpha (0xFF in high byte)
                ((colors.dominant shr 24) and 0xFF) shouldBe 0xFF
                ((colors.darkMuted shr 24) and 0xFF) shouldBe 0xFF
                ((colors.vibrant shr 24) and 0xFF) shouldBe 0xFF

                // RGB values should be in valid range (implicit by Int, but verify construction)
                val dominant = Color(colors.dominant)
                (dominant.red in 0..255) shouldBe true
                (dominant.green in 0..255) shouldBe true
                (dominant.blue in 0..255) shouldBe true
            }
        }

        test("handles PNG format") {
            runTest {
                // Given - PNG image
                val imageBytes = createSolidColorImage(Color.MAGENTA, format = "PNG")

                // When
                val colors = extractor.extractColors(imageBytes)

                // Then
                colors.shouldNotBeNull()
            }
        }

        test("handles JPEG format") {
            runTest {
                // Given - JPEG image
                val imageBytes = createSolidColorImage(Color.YELLOW, format = "JPEG")

                // When
                val colors = extractor.extractColors(imageBytes)

                // Then
                colors.shouldNotBeNull()
            }
        }
    })
