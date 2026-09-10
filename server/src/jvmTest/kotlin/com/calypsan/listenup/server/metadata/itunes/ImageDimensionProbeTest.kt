package com.calypsan.listenup.server.metadata.itunes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

class ImageDimensionProbeTest :
    FunSpec({
        // Minimal valid PNG header: 8-byte sig + length(13) + "IHDR" + width=800 + height=600.
        val pngHeader =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A, // signature
                0x00,
                0x00,
                0x00,
                0x0D, // IHDR length = 13
                0x49,
                0x48,
                0x44,
                0x52, // "IHDR"
                0x00,
                0x00,
                0x03,
                0x20.toByte(), // width = 800
                0x00,
                0x00,
                0x02,
                0x58, // height = 600
            )

        // Minimal JPEG: SOI (FFD8) + SOF0 (FFC0) segment len(17) precision(8) height=600 width=800.
        val jpegHeader =
            byteArrayOf(
                0xFF.toByte(),
                0xD8.toByte(), // SOI
                0xFF.toByte(),
                0xC0.toByte(), // SOF0
                0x00,
                0x11, // segment length = 17
                0x08, // precision
                0x02,
                0x58, // height = 600
                0x03,
                0x20.toByte(), // width = 800
                0x03, // components
            )

        test("parses PNG dimensions") {
            parseImageDimensions(pngHeader) shouldBe (800 to 600)
        }

        test("parses JPEG dimensions") {
            parseImageDimensions(jpegHeader) shouldBe (800 to 600)
        }

        test("returns null for non-image / truncated data") {
            parseImageDimensions(byteArrayOf(0x00, 0x01, 0x02)) shouldBe null
            parseImageDimensions(ByteArray(0)) shouldBe null
        }

        test("a probe of a URL the policy rejects makes no request at all") {
            var requests = 0
            val probe =
                ImageDimensionProbe(
                    HttpClient(
                        MockEngine {
                            requests++
                            respond(pngHeader, HttpStatusCode.OK)
                        },
                    ),
                )

            probe.probe("http://cdn.example.com/cover.png") shouldBe null
            requests shouldBe 0
        }

        test("a probe still reads dimensions when the remote ignores the Range hint") {
            // The remote answers with far more than the probe asked for. Behaviour must be
            // unchanged — the header still parses — while the read stays bounded by the seam.
            var rangeHeader: String? = null
            val probe =
                ImageDimensionProbe(
                    HttpClient(
                        MockEngine { request ->
                            rangeHeader = request.headers[HttpHeaders.Range]
                            respond(
                                content = pngHeader + ByteArray(OVERSIZED_TAIL_BYTES),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Image.PNG.toString()),
                            )
                        },
                    ),
                )

            probe.probe("https://cdn.example.com/cover.png") shouldBe (800 to 600)
            rangeHeader.shouldNotBeNull()
        }
    })

private const val OVERSIZED_TAIL_BYTES = 200_000
