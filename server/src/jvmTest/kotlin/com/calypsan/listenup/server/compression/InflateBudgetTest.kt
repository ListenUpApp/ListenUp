package com.calypsan.listenup.server.compression

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/** Compresses [data] with our own deflater, so the input needs nothing outside the codec package. */
private fun deflateBytes(data: ByteArray): Buffer =
    Buffer().also { out ->
        out.deflated().buffered().use { it.write(data) }
    }

/** A pattern that compresses heavily, so a tiny stream can declare a large expansion. */
private fun repetitive(size: Int): ByteArray = ByteArray(size) { (it % 4).toByte() }

/**
 * The decompressed-output ceiling.
 *
 * A DEFLATE block's decoded size is not RFC-bounded: a stream of a few hundred bytes can legitimately
 * expand by a thousandfold, and a crafted one has no ceiling at all. The budget is what turns "how
 * big will this get?" from a question the input answers into one the caller does — so these specs
 * pin that it declines *before* the buffer is allowed past the line, not after.
 */
class InflateBudgetTest :
    FunSpec({

        test("output under the budget round-trips unchanged") {
            val data = repetitive(10_000)

            InflateRawSource(deflateBytes(data), maxOutputBytes = 20_000).buffered().readByteArray() shouldBe data
        }

        test("output exactly at the budget round-trips — the ceiling is inclusive") {
            val data = repetitive(10_000)

            InflateRawSource(deflateBytes(data), maxOutputBytes = 10_000).buffered().readByteArray() shouldBe data
        }

        test("output past the budget throws MalformedDeflateException") {
            val exception =
                shouldThrow<MalformedDeflateException> {
                    InflateRawSource(deflateBytes(repetitive(200_000)), maxOutputBytes = 100_000).buffered().readByteArray()
                }

            exception.message shouldContain "budget"
        }

        // The check must run as bytes are produced, not when a read completes: one block can decode to
        // arbitrarily many bytes, so a per-read check would already have buffered them all. Counting
        // what the source hands out before it throws proves nothing past the budget ever existed.
        test("nothing beyond the budget is ever delivered") {
            val budget = 100_000L
            val inflater = InflateRawSource(deflateBytes(repetitive(200_000)), maxOutputBytes = budget)
            val out = Buffer()
            var delivered = 0L

            shouldThrow<MalformedDeflateException> {
                while (true) {
                    val n = inflater.readAtMostTo(out, 8192)
                    if (n < 0) break
                    delivered += n
                }
            }

            delivered shouldBeLessThanOrEqual budget
        }

        test("the default is unbounded, so existing callers see no change") {
            val data = repetitive(200_000)

            InflateRawSource(deflateBytes(data)).buffered().readByteArray() shouldBe data
            deflateBytes(data).inflated().buffered().readByteArray() shouldBe data
        }
    })
