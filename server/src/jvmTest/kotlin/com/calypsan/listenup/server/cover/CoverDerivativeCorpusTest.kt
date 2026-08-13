package com.calypsan.listenup.server.cover

import com.calypsan.listenup.server.io.deleteRecursively
import com.calypsan.listenup.server.io.readBytes
import com.calypsan.listenup.server.io.readEnv
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * The whole production path over a **real** cover library: decode at scale, resize, re-encode, cache.
 *
 * [com.calypsan.listenup.server.imaging.JpegCorpusTest] proves the decoder against this same corpus,
 * but only the decoder — the encoder was verified against ten files read back by PIL. This closes
 * that gap: every cover a real library holds goes through the exact pipeline `?w=` runs, and the
 * tally says what a warm-up of that library costs in wall time and disk.
 *
 * ⛔ **The corpus is never committed** — copyrighted artwork lifted out of real audiobooks. It lives
 * outside any git repo and this spec reads its location from `LISTENUP_COVER_CORPUS`, skipping
 * entirely when unset so CI stays hermetic. Run it with:
 *
 * ```
 * LISTENUP_COVER_CORPUS=~/Code/listenup/cover-corpus \
 *   ./gradlew :server:jvmTest --tests "com.calypsan.listenup.server.cover.CoverDerivativeCorpusTest"
 * ```
 *
 * ⚠️ **jvmTest deliberately, not commonTest.** The point of this spec is the *cost*, and the native
 * lane links a debug binary that runs the codec ~8x slower than the release binary that ships —
 * a number read off it would be worse than no number. The decoder's own corpus gate covers native.
 */
class CoverDerivativeCorpusTest :
    FunSpec({

        val corpus = readEnv(CORPUS_ENV)?.let(::Path)
        val present = corpus != null && SystemFileSystem.metadataOrNull(corpus)?.isDirectory == true

        test("every cover in a real library derives or declines, and the ladder costs what we think")
            .config(enabled = present, timeout = CORPUS_TIMEOUT) {
                val cacheDir = Path(Files.createTempDirectory("corpus-derivatives-").toString())
                val derivatives = CoverDerivatives(cacheDir)
                val files =
                    SystemFileSystem
                        .list(corpus!!)
                        .filter { SystemFileSystem.metadataOrNull(it)?.isDirectory != true }

                val generated = mutableMapOf<Int, Int>()
                val declined = mutableMapOf<Int, Int>()
                val escaped = mutableListOf<String>()
                var sourceBytes = 0L

                val started = TimeSource.Monotonic.markNow()
                for (file in files) {
                    val bytes = file.readBytes()
                    sourceBytes += bytes.size
                    // The corpus is one file per cover, so its name is as good a content key as a hash.
                    val key = file.name.substringBeforeLast('.').replace(OFFENDING_KEY_CHARS, "_")
                    for (rung in derivatives.rungs) {
                        // Throwable, not Exception: the pipeline's own catch-all handles Exception,
                        // so what this watches for is what it deliberately does not swallow.
                        val outcome =
                            try {
                                derivatives.warm(key, rung) { bytes }
                            } catch (_: Throwable) {
                                escaped += "${file.name}@$rung"
                                continue
                            }
                        val tally = if (outcome == WarmResult.GENERATED) generated else declined
                        tally[rung] = (tally[rung] ?: 0) + 1
                    }
                }
                val elapsed = started.elapsedNow()

                val cachedBytes =
                    SystemFileSystem
                        .list(cacheDir)
                        .sumOf { SystemFileSystem.metadataOrNull(it)?.size ?: 0L }

                println(
                    buildString {
                        appendLine("cover derivative corpus: ${files.size} covers in $elapsed")
                        derivatives.rungs.forEach { rung ->
                            appendLine("  ${rung}px: ${generated[rung] ?: 0} generated, ${declined[rung] ?: 0} declined")
                        }
                        appendLine("  originals ${sourceBytes / MB}MB -> derivatives ${cachedBytes / MB}MB")
                        appendLine("  ${elapsed.inWholeMilliseconds / files.size.coerceAtLeast(1)}ms per cover")
                    },
                )
                deleteRecursively(cacheDir)

                // An escape is a bug: an underivable cover must leave the original serving.
                withClue("threw instead of declining: ${escaped.take(REPORTED_FAILURES)}") { escaped.shouldBeEmpty() }

                // Measured 2026-08-13 over the 1195-cover corpus: **1114 of 1195 (93%)** produce the
                // 300px rung. The 81 that decline are honest — 13 WebPs the codec does not read, and
                // ~68 covers narrower than 1200px, which is the smallest source a 300px rung can come
                // from when the decoder's reductions stop at source/4. The bar sits below that to
                // catch a *collapse* (a broken resize or encoder takes it to roughly zero) rather
                // than to police the handful of covers a real library always has.
                withClue("only ${generated[SMALL_RUNG]} of ${files.size} covers produced a ${SMALL_RUNG}px derivative") {
                    (generated[SMALL_RUNG] ?: 0) * PERCENT / files.size shouldBeGreaterThan MINIMUM_GENERATED_PERCENT
                }
            }
    })

private val OFFENDING_KEY_CHARS = Regex("[@/\\\\]")
private val CORPUS_TIMEOUT = 30.minutes
private const val CORPUS_ENV = "LISTENUP_COVER_CORPUS"
private const val SMALL_RUNG = 300
private const val REPORTED_FAILURES = 10
private const val PERCENT = 100
private const val MINIMUM_GENERATED_PERCENT = 85
private const val MB = 1024 * 1024
