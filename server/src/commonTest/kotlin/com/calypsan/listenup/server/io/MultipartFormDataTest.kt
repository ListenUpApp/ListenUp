package com.calypsan.listenup.server.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
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
    })

private const val BOUNDARY = "BOUNDARY"
private const val LIMIT = 1L shl 30

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
