package com.calypsan.listenup.server.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.utils.io.ByteReadChannel
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.random.Random

/**
 * Exercises the hand-rolled multipart parser against crafted bodies. Runs on the JVM test runner
 * (Kotest), so every branch — boundary straddling, near-boundary body bytes, size limits — is covered
 * fast; the native runtime reuses the identical code through `streamFirstFilePartTo`'s native actual.
 */
class MultipartFormDataTest :
    FunSpec({

        test("extracts a single file part byte-for-byte") {
            val body = "The Way of Kings, chapter one.".encodeToByteArray()
            val multipart = buildMultipart(BOUNDARY, listOf(filePart("backup", "lib.zip", body)))

            extractFilePart(multipart) shouldBe body
        }

        test("skips form fields before the file part") {
            val body = byteArrayOf(9, 8, 7, 6, 5)
            val multipart =
                buildMultipart(
                    BOUNDARY,
                    listOf(
                        fieldPart("kind", "library".encodeToByteArray()),
                        fieldPart("overwrite", "true".encodeToByteArray()),
                        filePart("backup", "lib.zip", body),
                    ),
                )

            extractFilePart(multipart) shouldBe body
        }

        test("captures the first file part and ignores later parts") {
            val first = "first".encodeToByteArray()
            val multipart =
                buildMultipart(
                    BOUNDARY,
                    listOf(
                        filePart("a", "first.bin", first),
                        filePart("b", "second.bin", "second".encodeToByteArray()),
                    ),
                )

            extractFilePart(multipart) shouldBe first
        }

        test("returns false and never opens a sink when there is no file part") {
            val multipart =
                buildMultipart(BOUNDARY, listOf(fieldPart("kind", "library".encodeToByteArray())))
            var sinkOpened = false

            val found =
                streamFirstFilePart(ByteReadChannel(multipart), BOUNDARY, LIMIT) {
                    sinkOpened = true
                    Buffer()
                }

            found.shouldBeFalse()
            sinkOpened.shouldBeFalse()
        }

        test("handles an empty file part") {
            val multipart = buildMultipart(BOUNDARY, listOf(filePart("backup", "empty.zip", ByteArray(0))))

            extractFilePart(multipart) shouldBe ByteArray(0)
        }

        test("is not fooled by near-boundary byte sequences inside the body") {
            // Contains "\r\n--BOUNDAR" (one char short) and "z--BOUNDARY" (no leading CRLF) — neither is
            // the real "\r\n--BOUNDARY" delimiter, so the whole payload must survive intact.
            val body = "abc\r\n--BOUNDAR\r\nxyz--BOUNDARY-tail".encodeToByteArray()
            val multipart = buildMultipart(BOUNDARY, listOf(filePart("backup", "tricky.bin", body)))

            extractFilePart(multipart) shouldBe body
        }

        test("enforces the size limit on the file part") {
            val body = ByteArray(1_000) { it.toByte() }
            val multipart = buildMultipart(BOUNDARY, listOf(filePart("backup", "big.zip", body)))

            shouldThrow<MultipartPartTooLargeException> {
                streamFirstFilePart(ByteReadChannel(multipart), BOUNDARY, formFieldLimit = 999) { Buffer() }
            }
        }

        test("accepts a file part exactly at the size limit") {
            val body = ByteArray(1_000) { it.toByte() }
            val multipart = buildMultipart(BOUNDARY, listOf(filePart("backup", "exact.zip", body)))

            extractFilePart(multipart, limit = 1_000) shouldBe body
        }

        test("throws on a body with no boundary at all") {
            shouldThrow<MalformedMultipartException> {
                streamFirstFilePart(ByteReadChannel("not multipart".encodeToByteArray()), BOUNDARY, LIMIT) {
                    Buffer()
                }
            }
        }

        // Tiny buffer capacities force the boundary and headers to straddle fills, stressing the
        // holdback scanner that a 64 KiB production buffer would never exercise.
        listOf(13, 17, 31, 64, 257).forEach { capacity ->
            test("round-trips a large random body with a $capacity-byte buffer") {
                val body = Random(capacity.toLong()).nextBytes(20_000)
                val multipart = buildMultipart(BOUNDARY, listOf(filePart("backup", "rand.bin", body)))

                extractFilePart(multipart, capacity = capacity) shouldBe body
            }
        }

        // The per-part *body* limit was always enforced; the header block was not. A header line with
        // no terminator made the reader double its buffer on every fill until the channel or the heap
        // ran out. The cap has to fire on a line far shorter than either.
        test("an unterminated header line is refused rather than buffered without limit") {
            val body = Buffer()
            body.write("--$BOUNDARY\r\n".encodeToByteArray())
            body.write(ByteArray(UNTERMINATED_LINE_BYTES) { 'a'.code.toByte() })

            val exception =
                shouldThrow<MalformedMultipartException> {
                    streamFirstFilePart(ByteReadChannel(body.readByteArray()), BOUNDARY, LIMIT) { Buffer() }
                }

            exception.message shouldContain "exceeded"
        }

        // Every header line is consumed and kept, so a part that never sends the blank line grows the
        // header list without bound. The block as a whole has a ceiling, not just each line.
        test("a header block that never ends is refused") {
            val flood = List(HEADER_LINE_FLOOD) { "X-Padding-$it: y" }
            val part = CraftedPart(flood + filePart("backup", "lib.zip", byteArrayOf(1)).headers, byteArrayOf(1))
            val multipart = buildMultipart(BOUNDARY, listOf(part))

            shouldThrow<MalformedMultipartException> {
                streamFirstFilePart(ByteReadChannel(multipart), BOUNDARY, LIMIT) { Buffer() }
            }
        }

        // The buffer can no longer grow past its ceiling, so a delimiter that would not fit under it
        // could never be matched: the reader would stall instead of scanning. Refuse it at construction.
        test("a boundary longer than the buffer ceiling is rejected up front") {
            shouldThrow<IllegalArgumentException> {
                streamFirstFilePart(ByteReadChannel(ByteArray(0)), "b".repeat(OVERLONG_BOUNDARY_CHARS), LIMIT) { Buffer() }
            }
        }

        // Regression control for the caps: a long but legitimate header block — a filename that fills
        // a couple of KiB and a handful of extra headers — parses, even through a tiny buffer that
        // has to grow to hold the line.
        listOf(13, 64 * 1024).forEach { capacity ->
            test("ordinary headers still parse with a $capacity-byte buffer") {
                val body = "still here".encodeToByteArray()
                val longName = "novel-".repeat(LONG_FILENAME_REPEATS) + ".zip"
                val extras = List(5) { "X-Client-Hint-$it: value-$it" }
                val part = CraftedPart(filePart("backup", longName, body).headers + extras, body)

                extractFilePart(buildMultipart(BOUNDARY, listOf(part)), capacity = capacity) shouldBe body
            }
        }
    })

private const val BOUNDARY = "BOUNDARY"
private const val LIMIT = 1L shl 30

/** 1 MiB without a CRLF — far past the 8 KiB line cap, far short of anything that strains memory. */
private const val UNTERMINATED_LINE_BYTES = 1 shl 20

/** Enough ~17-byte lines to pass the 16 KiB block cap several times over. */
private const val HEADER_LINE_FLOOD = 4_096

/** Its delimiter, doubled, would exceed the 256 KiB buffer ceiling. */
private const val OVERLONG_BOUNDARY_CHARS = 200_000

/** A ~2 KiB filename: legitimate, and larger than any real one, yet a quarter of the line cap. */
private const val LONG_FILENAME_REPEATS = 340

private suspend fun extractFilePart(
    multipart: ByteArray,
    limit: Long = LIMIT,
    capacity: Int = 64 * 1024,
): ByteArray {
    val captured = Buffer()
    val found =
        streamFirstFilePart(ByteReadChannel(multipart), BOUNDARY, limit, capacity) { captured }
    check(found) { "expected a file part" }
    return captured.readByteArray()
}

private class CraftedPart(
    val headers: List<String>,
    val body: ByteArray,
)

private fun filePart(
    name: String,
    filename: String,
    body: ByteArray,
) = CraftedPart(
    listOf(
        "Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"",
        "Content-Type: application/octet-stream",
    ),
    body,
)

private fun fieldPart(
    name: String,
    body: ByteArray,
) = CraftedPart(listOf("Content-Disposition: form-data; name=\"$name\""), body)

private fun buildMultipart(
    boundary: String,
    parts: List<CraftedPart>,
): ByteArray {
    val out = Buffer()
    for (part in parts) {
        out.write("--$boundary\r\n".encodeToByteArray())
        for (header in part.headers) out.write("$header\r\n".encodeToByteArray())
        out.write("\r\n".encodeToByteArray())
        out.write(part.body)
        out.write("\r\n".encodeToByteArray())
    }
    out.write("--$boundary--\r\n".encodeToByteArray())
    return out.readByteArray()
}
