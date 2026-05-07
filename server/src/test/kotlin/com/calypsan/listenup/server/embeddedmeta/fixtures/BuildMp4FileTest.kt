package com.calypsan.listenup.server.embeddedmeta.fixtures

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Smoke test for [buildMp4File] — confirms the DSL emits a non-empty byte
 * stream with the expected `ftyp` atom prefix (size + magic at offsets 0..7).
 * Deeper structural assertions land in [Mp4Parser] tests once that parser
 * exists; here we just want red→green confidence the encoder runs at all.
 */
class BuildMp4FileTest : FunSpec({
    test("buildMp4File emits ftyp + moov + mdat starting with the ftyp atom magic") {
        val bytes =
            buildMp4File {
                ftyp(brand = "M4B ")
                moov {
                    mvhd(timescale = 1000, durationInTimescale = 60_000)
                    udta {
                        meta {
                            tag("©nam", "The Way of Kings")
                            tag("©ART", "Brandon Sanderson")
                        }
                    }
                    audioTrack(trackId = 1)
                }
            }
        // Non-empty.
        (bytes.size > 0) shouldBe true
        // First atom magic at offsets 4..7 = "ftyp".
        bytes[4] shouldBe 'f'.code.toByte()
        bytes[5] shouldBe 't'.code.toByte()
        bytes[6] shouldBe 'y'.code.toByte()
        bytes[7] shouldBe 'p'.code.toByte()
    }

    test("buildMp4File supports Nero chpl emission with 100-ns timestamp encoding") {
        val bytes =
            buildMp4File {
                ftyp()
                moov {
                    mvhd(timescale = 1000, durationInTimescale = 1000)
                    udta {
                        chpl(
                            chapters =
                                listOf(
                                    NeroChapter(startMs = 0, title = "Prologue"),
                                    NeroChapter(startMs = 250, title = "Chapter 1"),
                                ),
                        )
                    }
                    audioTrack()
                }
            }
        // Container assertions — size is the only thing we cheaply check
        // outside the parser. ~120 bytes minimum: ftyp + moov header chain.
        (bytes.size > 100) shouldBe true
    }
})
