package com.calypsan.listenup.server.cover

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.imaging.PixelBuffer
import com.calypsan.listenup.server.imaging.encodeJpeg
import com.calypsan.listenup.server.imaging.packPixel
import com.calypsan.listenup.server.io.writeBytes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.Files

/**
 * The warm-up and the sweep — the two halves of keeping the derivative cache honest.
 *
 * Both are **allowed to fail**: `?w=` generates on demand regardless, so nothing here is on a
 * correctness path. What these tests pin is that the pass is cheap to repeat (it must not
 * regenerate what it already has), that one bad cover cannot end the pass, and that the sweep
 * deletes only what it is sure about.
 */
class CoverDerivativeMaintenanceTest :
    FunSpec({

        fun tempDir(): Path = Path(Files.createTempDirectory("coverwarm-").toString())

        fun maintenance(
            dir: Path,
            covers: List<LiveCover>,
            originalBytes: suspend (BookId) -> ByteArray?,
        ) = CoverDerivativeMaintenance(
            derivatives = CoverDerivatives(dir),
            liveCovers = { covers },
            originalBytes = originalBytes,
        )

        // The source is 1200px, so the 300 rung is reachable and the 600 rung is not (the decoder
        // caps at source/4). That asymmetry is the realistic case and worth asserting directly:
        // an unreachable rung must be a quiet skip, not a failure that abandons the book.
        test("writes the rungs the source can reach and skips the ones it cannot") {
            val dir = tempDir()
            runTest {
                maintenance(dir, listOf(LiveCover(BookId("b1"), HASH))) { wideJpeg }.runOnce()

                SystemFileSystem.exists(Path(dir, "$HASH@300.jpg")) shouldBe true
                SystemFileSystem.exists(Path(dir, "$HASH@600.jpg")) shouldBe false
            }
        }

        // The loop runs this pass every day for the life of the process. Over a warm library it has
        // to cost nothing — and note "nothing" includes the rung that DECLINES, which writes no
        // file and so leaves nothing on disk to remember the attempt by.
        test("repeat passes do not read the originals again") {
            val dir = tempDir()
            runTest {
                var reads = 0
                val pass =
                    maintenance(dir, listOf(LiveCover(BookId("b1"), HASH))) {
                        reads++
                        wideJpeg
                    }

                pass.runOnce()
                val afterFirst = reads
                pass.runOnce()
                pass.runOnce()

                // One read for both rungs: the reachable one derives from it, the unreachable one
                // must not pay for a second read just to discover it cannot use it.
                afterFirst shouldBe 1
                reads shouldBe afterFirst
            }
        }

        test("a cover whose bytes cannot be read does not end the pass") {
            val dir = tempDir()
            runTest {
                val covers =
                    listOf(
                        LiveCover(BookId("gone"), "deadhash"),
                        LiveCover(BookId("b1"), HASH),
                    )

                maintenance(dir, covers) { if (it.value == "gone") null else wideJpeg }.runOnce()

                SystemFileSystem.exists(Path(dir, "deadhash@300.jpg")) shouldBe false
                SystemFileSystem.exists(Path(dir, "$HASH@300.jpg")) shouldBe true
            }
        }

        // Content-addressing means a re-covered book simply stops referring to its old derivative.
        // Nothing deletes it at the time — this sweep is the whole reclamation story.
        test("sweeps a derivative whose hash belongs to no live cover") {
            val dir = tempDir()
            runTest {
                SystemFileSystem.createDirectories(dir)
                val stale = Path(dir, "oldhash@300.jpg")
                stale.writeBytes(wideJpeg)

                maintenance(dir, listOf(LiveCover(BookId("b1"), HASH))) { wideJpeg }.runOnce()

                SystemFileSystem.exists(stale) shouldBe false
                SystemFileSystem.exists(Path(dir, "$HASH@300.jpg")) shouldBe true
            }
        }

        // Deleting something we cannot explain is how a cache sweep turns into data loss. Anything
        // that is not shaped like a derivative we wrote is left exactly where it is.
        test("leaves files it did not write alone") {
            val dir = tempDir()
            runTest {
                SystemFileSystem.createDirectories(dir)
                val stranger = Path(dir, "notes.txt")
                stranger.writeBytes("do not delete me".encodeToByteArray())

                maintenance(dir, emptyList()) { null }.runOnce()

                SystemFileSystem.exists(stranger) shouldBe true
            }
        }
    })

/**
 * A real 1200px JPEG, encoded once for the whole spec — four quadrants, so a derivative that is
 * blank or transposed cannot pass. 1200 reaches the 300 rung and not the 600 one.
 */
private val wideJpeg: ByteArray by lazy {
    val size = 1200
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
    encodeJpeg(PixelBuffer(size, size, pixels), quality = 90)
}

private const val HASH = "abc123"
