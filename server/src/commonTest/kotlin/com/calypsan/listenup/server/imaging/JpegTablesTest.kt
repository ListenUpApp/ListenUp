package com.calypsan.listenup.server.imaging

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * Structural invariants of the standard tables.
 *
 * These exist because the tables are **parsed from text at startup** rather than written as array
 * literals — the formatter explodes a literal to one entry per line and destroys the 8x8 shape that
 * makes a quantisation matrix readable. Text buys that back and costs compile-time checking, so the
 * checking moves here: a dropped digit or a fat-fingered row now fails a test instead of silently
 * producing a codec that writes files nothing can read.
 */
class JpegTablesTest :
    FunSpec({

        test("the zig-zag order is a permutation of every position in a block") {
            ZIGZAG.size shouldBe BLOCK_COEFFICIENTS
            ZIGZAG.sorted() shouldContainExactly (0 until BLOCK_COEFFICIENTS).toList()
        }

        test("quantisation tables are whole blocks of legal 8-bit values") {
            for ((name, table) in listOf("luma" to BASE_QUANT_LUMA, "chroma" to BASE_QUANT_CHROMA)) {
                withClue(name) {
                    table.size shouldBe BLOCK_COEFFICIENTS
                    table.min() shouldBeGreaterThan 0
                    table.max() shouldBeLessThanOrEqual 255
                }
            }
        }

        // A Huffman table declares how many codes exist at each of the 16 possible lengths. If that
        // does not agree with the symbol list, the canonical construction walks off the end and every
        // symbol past the break gets the wrong code — which decodes as plausible garbage, not a crash.
        test("every Huffman table's counts agree with its symbol list") {
            for ((name, counts, values) in HUFFMAN_TABLES) {
                withClue(name) {
                    counts.size shouldBe MAX_CODE_LENGTH
                    counts.sum() shouldBe values.size
                    values.min() shouldBeGreaterThan -1
                    values.max() shouldBeLessThanOrEqual 255
                }
            }
        }

        // Kraft's inequality: a prefix code cannot promise more codes at a length than the tree has
        // room for. Violating it means two symbols share a prefix, so a decoder cannot tell them
        // apart — the one property of a Huffman table that a size check alone would not catch.
        test("every Huffman table is a valid prefix code") {
            for ((name, counts, _) in HUFFMAN_TABLES) {
                withClue("$name over-subscribes the code space") {
                    // Incremental form: two codes exist at length 1, each unused prefix doubles at
                    // the next length, and each declared code consumes one. Going negative means
                    // the table promised codes the tree cannot supply.
                    var available = 2
                    for (length in 1..MAX_CODE_LENGTH) {
                        available -= counts[length - 1]
                        withClue("ran out at length $length") { available shouldBeGreaterThan -1 }
                        available *= 2
                    }
                }
            }
        }

        test("quality scaling clamps into the legal range at both extremes") {
            for (quality in listOf(-100, 0, 1, 50, 92, 100, 1000)) {
                withClue("quality $quality") {
                    val table = scaleQuantTable(BASE_QUANT_LUMA, quality)
                    table.size shouldBe BLOCK_COEFFICIENTS
                    table.min() shouldBeGreaterThan 0
                    table.max() shouldBeLessThanOrEqual 255
                }
            }
        }

        // Quality 50 is the hinge of the libjpeg formula: the scale factor is exactly 100, so the
        // base table passes through untouched. A drifting formula shows up here first.
        test("quality 50 is the base table unchanged") {
            scaleQuantTable(BASE_QUANT_LUMA, 50).toList() shouldContainExactly BASE_QUANT_LUMA.toList()
        }
    })

private val HUFFMAN_TABLES =
    listOf(
        Triple("DC luma", STANDARD_DC_LUMA_COUNTS, STANDARD_DC_LUMA_VALUES),
        Triple("AC luma", STANDARD_AC_LUMA_COUNTS, STANDARD_AC_LUMA_VALUES),
        Triple("DC chroma", STANDARD_DC_CHROMA_COUNTS, STANDARD_DC_CHROMA_VALUES),
        Triple("AC chroma", STANDARD_AC_CHROMA_COUNTS, STANDARD_AC_CHROMA_VALUES),
    )
