package com.calypsan.listenup.server.cover

import com.calypsan.listenup.server.imaging.PixelBuffer
import com.calypsan.listenup.server.imaging.decodeJpeg
import com.calypsan.listenup.server.imaging.encodeJpeg
import com.calypsan.listenup.server.imaging.packPixel
import com.calypsan.listenup.server.io.deleteRecursively
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/**
 * The derivative cache — the authoritative path for a sized cover.
 *
 * The ladder's real widths are deliberately absent from the cache's own tests: rung selection is a
 * pure function and is tested as one, while the cache is exercised at 16px from a 64px source. A
 * real 300px rung needs a 1200px source, and encoding 1.4M pixels per test would cost the native
 * lane minutes to prove plumbing a 64px image proves just as well.
 */
class CoverDerivativesTest :
    FunSpec({

        /** Four quadrants, so a derivative that is transposed or blank cannot pass as correct. */
        fun quadrants(size: Int): PixelBuffer {
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val left = x < size / 2
                    val top = y < size / 2
                    pixels[y * size + x] =
                        when {
                            top && left -> packPixel(255, 200, 40, 40)
                            top -> packPixel(255, 40, 40, 200)
                            left -> packPixel(255, 40, 180, 40)
                            else -> packPixel(255, 230, 230, 230)
                        }
                }
            }
            return PixelBuffer(size, size, pixels)
        }

        fun sourceJpeg(): ByteArray = encodeJpeg(quadrants(SOURCE), quality = 90)

        fun tempDir(): Path =
            Path(SystemTemporaryDirectory, "coverderiv-${Random.nextLong().toString(HEX_RADIX)}")
                .also { SystemFileSystem.createDirectories(it) }

        test("generates a JPEG derivative at the requested width") {
            val dir = tempDir()
            val derivatives = CoverDerivatives(dir)

            val bytes = derivatives.derivative(HASH, DERIVED) { sourceJpeg() }.shouldNotBeNull()

            decodeJpeg(bytes, maxWidth = DERIVED / 4).shouldNotBeNull().width shouldBe DERIVED / 4
            deleteRecursively(dir)
        }

        // Content-addressing by the cover hash is what lets a re-covered book skip invalidation
        // entirely: new artwork is a new hash is a new file, and the stale one is never asked for.
        test("writes the derivative keyed by cover hash and width") {
            val dir = tempDir()
            val derivatives = CoverDerivatives(dir)

            derivatives.derivative(HASH, DERIVED) { sourceJpeg() }

            SystemFileSystem.exists(Path(dir, "$HASH@$DERIVED.jpg")) shouldBe true
            deleteRecursively(dir)
        }

        test("serves the cached file rather than decoding the source again") {
            val dir = tempDir()
            val derivatives = CoverDerivatives(dir)
            val first = derivatives.derivative(HASH, DERIVED) { sourceJpeg() }.shouldNotBeNull()

            // A source that is gone entirely: the only way to answer is from the cache.
            val second = derivatives.derivative(HASH, DERIVED) { null }.shouldNotBeNull()

            second shouldBe first
            deleteRecursively(dir)
        }

        // The decoder's reductions stop at source/4, so a width it cannot reach must decline and
        // let the caller serve the original — never upscale to fill the request.
        test("declines a width the reduction ladder cannot reach") {
            val dir = tempDir()
            val derivatives = CoverDerivatives(dir)

            derivatives.derivative(HASH, SOURCE / 2) { sourceJpeg() }.shouldBeNull()
            deleteRecursively(dir)
        }

        test("declines an undecodable source without failing") {
            val dir = tempDir()
            val derivatives = CoverDerivatives(dir)

            derivatives.derivative(HASH, DERIVED) { "not an image".encodeToByteArray() }.shouldBeNull()
            deleteRecursively(dir)
        }

        test("declines when the cover has no bytes to derive from") {
            val dir = tempDir()
            val derivatives = CoverDerivatives(dir)

            derivatives.derivative(HASH, DERIVED) { null }.shouldBeNull()
            deleteRecursively(dir)
        }

        // A grid paints many tiles at once, so the first request for a cold cover arrives as a
        // burst for the same key. Encoding once per caller would multiply the one cost this design
        // is allowed to pay.
        test("concurrent callers for the same derivative generate it once") {
            val dir = tempDir()
            val derivatives = CoverDerivatives(dir)
            var loads = 0

            val results =
                coroutineScope {
                    List(CONCURRENT_CALLERS) {
                        async {
                            derivatives.derivative(HASH, DERIVED) {
                                loads++
                                yield() // Hold the key across a suspension so the race is real.
                                sourceJpeg()
                            }
                        }
                    }.awaitAll()
                }

            loads shouldBe 1
            results.forEach { it.shouldNotBeNull() shouldBe results.first() }
            deleteRecursively(dir)
        }

        test("refuses a hash that would escape the derivative directory") {
            val dir = tempDir()
            val derivatives = CoverDerivatives(dir)

            derivatives.derivative("../escape", DERIVED) { sourceJpeg() }.shouldBeNull()
            deleteRecursively(dir)
        }

        test("rungFor picks the smallest rung at or above the request") {
            val derivatives = CoverDerivatives(Path(SystemTemporaryDirectory))

            derivatives.rungFor(1) shouldBe SMALL_RUNG
            derivatives.rungFor(SMALL_RUNG - 1) shouldBe SMALL_RUNG
            derivatives.rungFor(SMALL_RUNG) shouldBe SMALL_RUNG
            derivatives.rungFor(SMALL_RUNG + 1) shouldBe LARGE_RUNG
            derivatives.rungFor(LARGE_RUNG) shouldBe LARGE_RUNG
        }

        // Past the ladder's top rung the original bytes are the right answer, so a larger request
        // is not an error — it is a decline that routes the caller back to the original.
        test("rungFor declines a request past the top of the ladder") {
            val derivatives = CoverDerivatives(Path(SystemTemporaryDirectory))

            derivatives.rungFor(LARGE_RUNG + 1).shouldBeNull()
            derivatives.rungFor(FULL_SIZE_COVER).shouldBeNull()
        }
    })

/** 64px: eight luma blocks across, four MCUs at 4:2:0 — enough for interleaving to be real. */
private const val SOURCE = 64

/** The 1/4 reduction of [SOURCE] — the largest scale the decoder will reach for it. */
private const val DERIVED = SOURCE / 4

private const val SMALL_RUNG = 300
private const val LARGE_RUNG = 600
private const val FULL_SIZE_COVER = 2400
private const val CONCURRENT_CALLERS = 5
private const val HEX_RADIX = 16
private const val HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
