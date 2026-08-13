package com.calypsan.listenup.server.imaging

import com.calypsan.listenup.server.io.readBytes
import com.calypsan.listenup.server.io.readEnv
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration.Companion.minutes

/**
 * The gate that matters: every JPEG in a **real** cover library, decoded.
 *
 * A hand-written decoder's risk is not the synthetic fixture it was built against — it is the files
 * people actually have. Progressive scans, restart intervals, subsampling ratios and table
 * redefinitions arrive in combinations no fixture anticipates, and the failure they produce is a
 * plausible-looking wrong picture rather than a crash. So the bar here is a *tally*, not a spot
 * check: what share of a genuine library do we render, and does anything escape.
 *
 * ⛔ **The corpus is never committed** — it is copyrighted artwork lifted out of real audiobooks.
 * It lives outside any git repo and this spec reads its location from `LISTENUP_COVER_CORPUS`,
 * skipping entirely when that is unset so CI stays hermetic. Run it locally with:
 *
 * ```
 * LISTENUP_COVER_CORPUS=~/Code/listenup/cover-corpus ./gradlew :server:jvmTest --tests …JpegCorpusTest
 * ```
 */
class JpegCorpusTest :
    FunSpec({

        val corpus = readEnv(CORPUS_ENV)?.let(::Path)
        val present = corpus != null && SystemFileSystem.metadataOrNull(corpus)?.isDirectory == true

        // Kotest's default 120s does not survive a real library: 1195 covers take ~40s on the JVM
        // but ~8m30s on Kotlin/Native, which is the target that actually ships. That gap is a
        // finding in its own right — see the note on native decode cost in the plan — but a walk of
        // someone's whole library is inherently long, and timing it out proves nothing about the
        // decoder.
        test("every JPEG in a real cover library decodes at a derivative scale")
            .config(enabled = present, timeout = CORPUS_TIMEOUT) {
                val files =
                    SystemFileSystem
                        .list(corpus!!)
                        .filter { SystemFileSystem.metadataOrNull(it)?.isDirectory != true }

                var jpegs = 0
                var decoded = 0
                var coarseOnly = 0
                val undecodable = mutableListOf<String>()
                val escaped = mutableListOf<String>()
                val otherFormats = mutableListOf<String>()

                for (file in files) {
                    val bytes = file.readBytes()
                    if (!looksLikeJpeg(bytes)) {
                        otherFormats += file.name
                        continue
                    }
                    jpegs++

                    // Throwable, not Exception: the decoder's own catch-all handles Exception, so what
                    // this is watching for is the class it deliberately does not swallow — a stack
                    // overflow from a malformed table walk, or an allocation driven by a bad header.
                    val image =
                        try {
                            decodeJpeg(bytes, maxWidth = DERIVATIVE_WIDTH)
                                ?: decodeJpeg(bytes, maxWidth = 1)?.also { coarseOnly++ }
                        } catch (_: Throwable) {
                            escaped += file.name
                            continue
                        }

                    if (image == null) {
                        undecodable += file.name
                        continue
                    }
                    decoded++

                    withClue("${file.name} decoded to ${image.width}x${image.height} with ${image.pixels.size} pixels") {
                        image.width shouldBeGreaterThan 0
                        image.height shouldBeGreaterThan 0
                        image.pixels.size shouldBe image.width * image.height
                    }
                }

                println(
                    "cover corpus: ${files.size} files, $jpegs JPEG — $decoded decoded " +
                        "($coarseOnly only at 1/8), ${undecodable.size} undecodable, ${escaped.size} escaped, " +
                        "${otherFormats.size} not JPEG",
                )

                // An escape is a bug, full stop: an undecodable cover must leave the original serving.
                withClue("threw instead of declining: ${escaped.take(REPORTED_FAILURES)}") { escaped.shouldBeEmpty() }

                // Half a library is the signature of a broken progressive path — roughly half of real
                // covers are progressive — which is the whole reason this decoder reconstructs scales
                // rather than declining SOF2.
                withClue("undecodable: ${undecodable.take(REPORTED_FAILURES)}") {
                    decoded * PERCENT / jpegs shouldBeGreaterThan MINIMUM_DECODED_PERCENT
                }
            }
    })

/** SOI. Enough to tell a JPEG from the WebP and PNG covers that share a real library. */
private fun looksLikeJpeg(bytes: ByteArray): Boolean = bytes.size > 1 && readUByte(bytes, 0) == SOI_HIGH && readUByte(bytes, 1) == SOI_LOW

private const val SOI_HIGH = 0xFF
private const val SOI_LOW = 0xD8
private val CORPUS_TIMEOUT = 30.minutes
private const val CORPUS_ENV = "LISTENUP_COVER_CORPUS"
private const val DERIVATIVE_WIDTH = 400
private const val REPORTED_FAILURES = 10
private const val PERCENT = 100
private const val MINIMUM_DECODED_PERCENT = 99
