package com.calypsan.listenup.server.metadata

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel

private const val TINY_CEILING_BYTES = 64L
private const val HOP_BUDGET = 2
private const val OVERSIZED_BYTES = 4096
private const val IMAGE_BYTE_COUNT = 8
private const val DECLARED_OVER_CEILING = "999999"
private const val PROBE_PREFIX_BYTES = 16

private val IMAGE_BYTES = ByteArray(IMAGE_BYTE_COUNT) { it.toByte() }

/**
 * The Tier-2 policy every fetch of an UNTRUSTED image URL must satisfy — a provider-returned cover
 * URL, a caller-supplied cover URL, or a contributor photo URL.
 *
 * Each test names a rejection: what the seam refuses to fetch, refuses to follow, and refuses to
 * read. The redirect cases matter most — a destination policy applied to the first URL but not to
 * each hop is not a policy at all, and "a redirect whose destination the policy rejects is refused"
 * is the proof that every hop is re-checked.
 *
 * Tier 1 (the shared client's time budget) is pinned separately by
 * [com.calypsan.listenup.server.di.MetadataHttpClientBoundsTest]; nothing here restricts the hosts
 * the shared client may reach, because operator-configured providers are legitimately internal.
 */
class BoundedImageFetchTest :
    FunSpec({

        test("a URL the policy rejects is never fetched at all") {
            var requests = 0
            val fetch =
                fetcher {
                    requests++
                    imageResponse()
                }

            val result = fetch.fetch("http://cdn.example.com/cover.jpg")

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
            requests shouldBe 0
        }

        test("a redirect whose destination the policy rejects is refused") {
            var requests = 0
            val fetch =
                fetcher {
                    requests++
                    redirectTo("http://cdn.example.com/elsewhere.jpg")
                }

            val result = fetch.fetch("https://cdn.example.com/cover.jpg")

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
            // Exactly one request: the hop target was validated and rejected before it was issued.
            requests shouldBe 1
        }

        test("a redirect to a destination the policy allows is followed") {
            val fetch =
                fetcher { request ->
                    if (request.url.host == "redirector.example.com") {
                        redirectTo("https://cdn.example.com/cover.jpg")
                    } else {
                        imageResponse()
                    }
                }

            val result = fetch.fetch("https://redirector.example.com/cover.jpg")

            result.bytes().toList() shouldBe IMAGE_BYTES.toList()
        }

        test("more redirect hops than the budget allows is refused") {
            var requests = 0
            val fetch =
                fetcher(maxRedirects = HOP_BUDGET) {
                    requests++
                    redirectTo("https://cdn.example.com/hop-$requests.jpg")
                }

            val result = fetch.fetch("https://cdn.example.com/cover.jpg")

            result.shouldBeInstanceOf<AppResult.Failure>()
            // The initial request plus HOP_BUDGET follows, and then the budget is spent.
            requests shouldBe HOP_BUDGET + 1
        }

        test("a response declaring more bytes than the ceiling is refused before its body is read") {
            // Proof of "refuses before reading": the body is EMPTY, so a buffer-then-check
            // implementation would read 0 bytes, stay under the ceiling, and succeed. Only a
            // declared-length check made before the first read can fail this.
            val fetch =
                fetcher(maxBytes = TINY_CEILING_BYTES) {
                    respond(
                        content = ByteReadChannel(ByteArray(0)),
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf(ContentType.Image.JPEG.toString()),
                                HttpHeaders.ContentLength to listOf(DECLARED_OVER_CEILING),
                            ),
                    )
                }

            val result = fetch.fetch("https://cdn.example.com/cover.jpg")

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<MetadataError.Malformed>()
        }

        test("a body that outgrows the ceiling while streaming is refused") {
            // No length is declared, so the ceiling can only be enforced by the read loop. This
            // proves the ceiling holds for an undeclared body; that the read stops early rather
            // than buffering the whole body first is what the preceding test proves.
            val fetch =
                fetcher(maxBytes = TINY_CEILING_BYTES) {
                    respond(
                        content = ByteReadChannel(ByteArray(OVERSIZED_BYTES)),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Image.JPEG.toString()),
                    )
                }

            val result = fetch.fetch("https://cdn.example.com/cover.jpg")

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<MetadataError.Malformed>()
        }

        test("a response that declares a non-image content type is refused") {
            val fetch =
                fetcher {
                    respond(
                        content = IMAGE_BYTES,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                    )
                }

            val result = fetch.fetch("https://cdn.example.com/cover.jpg")

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<MetadataError.Malformed>()
        }

        test("a response that declares no content type is fetched") {
            // Deliberate: origins that serve images without a Content-Type are real, and the
            // magic-number sniff in ImageStore (covers, photos) or the header parser (dimension
            // probe) is the authority on the bytes themselves. Tightening this to "image/* or
            // reject" would strand those covers, so this test makes it a decision, not an accident.
            val fetch = fetcher { respond(content = IMAGE_BYTES, status = HttpStatusCode.OK) }

            val result = fetch.fetch("https://cdn.example.com/cover.jpg")

            result.bytes().toList() shouldBe IMAGE_BYTES.toList()
        }

        test("a public https image URL is fetched and returned") {
            val fetch = fetcher { imageResponse() }

            val result = fetch.fetch("https://cdn.example.com/cover.jpg")

            result.bytes().toList() shouldBe IMAGE_BYTES.toList()
        }

        test("a leading-bytes fetch stops at the requested prefix when the remote ignores Range") {
            var rangeHeader: String? = null
            val fetch =
                fetcher { request ->
                    rangeHeader = request.headers[HttpHeaders.Range]
                    respond(
                        content = ByteReadChannel(ByteArray(OVERSIZED_BYTES)),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Image.JPEG.toString()),
                    )
                }

            val result = fetch.fetch("https://cdn.example.com/cover.jpg", leadingBytes = PROBE_PREFIX_BYTES)

            // The Range header is a hint the remote may ignore; the prefix cap is the guarantee.
            rangeHeader shouldBe "bytes=0-${PROBE_PREFIX_BYTES - 1}"
            result.bytes().size shouldBe PROBE_PREFIX_BYTES
        }
    })

private fun fetcher(
    maxBytes: Long = BoundedImageFetch.DEFAULT_MAX_IMAGE_BYTES,
    maxRedirects: Int = BoundedImageFetch.DEFAULT_MAX_REDIRECTS,
    handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): BoundedImageFetch =
    BoundedImageFetch(
        httpClient = HttpClient(MockEngine { request -> handler(request) }),
        maxBytes = maxBytes,
        maxRedirects = maxRedirects,
    )

/** Unwraps a successful fetch, failing the test with the typed error when it isn't one. */
private fun AppResult<ByteArray>.bytes(): ByteArray = shouldBeInstanceOf<AppResult.Success<*>>().data as ByteArray

private fun MockRequestHandleScope.imageResponse(): HttpResponseData =
    respond(
        content = IMAGE_BYTES,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Image.JPEG.toString()),
    )

private fun MockRequestHandleScope.redirectTo(location: String): HttpResponseData =
    respond(
        content = ByteArray(0),
        status = HttpStatusCode.Found,
        headers = headersOf(HttpHeaders.Location, location),
    )
