package com.calypsan.listenup.server.compression

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.io.Buffer
import kotlin.random.Random

class HuffmanTest :
    FunSpec({
        test("canonical codes from RFC §3.2.2 example lengths") {
            // RFC §3.2.2 example: 8 symbols A..H with lengths 3,3,3,3,3,2,4,4 → known canonical codes.
            val lengths = intArrayOf(3, 3, 3, 3, 3, 2, 4, 4)
            val (codes, codeLens) = canonicalCodes(lengths)
            codeLens[5] shouldBe 2 // 'F' length 2 → code 00
            codes[5] shouldBe 0b00
            codes[0] shouldBe 0b010 // 'A' length 3 → 010
            codes[6] shouldBe 0b1110 // 'G' length 4 → 1110
        }

        test("decode is the inverse of encode for every symbol") {
            val lengths = intArrayOf(3, 3, 3, 3, 3, 2, 4, 4)
            val (codes, codeLens) = canonicalCodes(lengths)
            val decoder = HuffmanDecoder(lengths)
            for (sym in lengths.indices) {
                val out = Buffer()
                val w = BitWriter(out)
                writeHuffmanCode(w, codes[sym], codeLens[sym])
                w.alignToByte()
                w.flush()
                decoder.decodeSymbol(BitReader(out)) shouldBe sym
            }
        }

        test("length-limited build never exceeds maxBits and stays a valid prefix code") {
            val freq = IntArray(20) { if (it == 0) 1000 else 1 } // skewed → naive would exceed 15 bits
            val lengths = buildLengthLimitedLengths(freq, maxBits = 15)
            lengths.forEachIndexed { i: Int, len: Int -> if (freq[i] > 0) len shouldBeLessThanOrEqualTo 15 }
            val kraft = lengths.filter { it > 0 }.sumOf { 1.0 / (1 shl it) }
            kraft shouldBe (1.0 plusOrMinus 1e-9)
        }

        test("length-limited redistribution yields a COMPLETE prefix code when natural depth exceeds maxBits") {
            // Fibonacci frequencies force a maximally-unbalanced tree whose natural longest code is ~n-1 bits
            // (19 here), well over the 15-bit DEFLATE limit — this actually exercises clip-and-redistribute.
            val freq =
                intArrayOf(
                    1,
                    1,
                    2,
                    3,
                    5,
                    8,
                    13,
                    21,
                    34,
                    55,
                    89,
                    144,
                    233,
                    377,
                    610,
                    987,
                    1597,
                    2584,
                    4181,
                    6765,
                )
            val lengths = buildLengthLimitedLengths(freq, maxBits = 15)
            lengths.filter { it > 0 }.forEach { it shouldBeLessThanOrEqualTo 15 }
            // COMPLETE, not merely valid: Σ 2^-len == 1. An incomplete litlen/dist code is rejected by RFC-1951 inflate.
            val kraft = lengths.filter { it > 0 }.sumOf { 1.0 / (1 shl it) }
            kraft shouldBe (1.0 plusOrMinus 1e-9)
        }

        test("length-limited build yields a COMPLETE prefix code for random 286-symbol litlen alphabets") {
            val rng = Random(20_260_625)
            repeat(50) {
                val freq = IntArray(286) { if (rng.nextInt(4) == 0) 0 else rng.nextInt(1, 100_000) }
                val usedCount = freq.count { it > 0 }
                val lengths = buildLengthLimitedLengths(freq, maxBits = 15)
                lengths.filter { it > 0 }.forEach { it shouldBeLessThanOrEqualTo 15 }
                if (usedCount >= 2) {
                    val kraft = lengths.filter { it > 0 }.sumOf { 1.0 / (1 shl it) }
                    kraft shouldBe (1.0 plusOrMinus 1e-9)
                }
            }
        }

        test("length-limited build: single used symbol gets length 1") {
            val freq = IntArray(10).also { it[3] = 42 }
            val lengths = buildLengthLimitedLengths(freq, maxBits = 15)
            lengths[3] shouldBe 1
            lengths.filterIndexed { i: Int, _: Int -> i != 3 }.all { it == 0 } shouldBe true
        }
    })
