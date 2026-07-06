package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest

/**
 * Endpoint contract tests for [ImageApi].
 *
 * The live Kotlin server serves contributor photos at `GET /api/v1/contributors/{id}/photo`
 * (see `MetadataImageRoutes`). There is no `/image` route, so a request
 * to `/image` 404s and contributor-image caching silently fails. This pins the path so the drift
 * can't return.
 */
class ImageApiTest :
    FunSpec({

        fun apiWithRecorder(record: (String) -> Unit): ImageApi {
            val engine =
                MockEngine { request ->
                    record(request.url.encodedPath)
                    respond(
                        content = byteArrayOf(1, 2, 3),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                    )
                }
            val client = HttpClient(engine)
            val factory = mock<ApiClientFactory>()
            everySuspend { factory.getClient() } returns client
            return ImageApi(clientFactory = factory, serverConfig = mock<ServerConfig>())
        }

        test("downloadContributorImage hits the /photo endpoint the Kotlin server serves") {
            runTest {
                var requestedPath: String? = null
                val api = apiWithRecorder { requestedPath = it }

                val result = api.downloadContributorImage("contrib-1")

                result.shouldBeInstanceOf<AppResult.Success<ByteArray>>()
                requestedPath shouldBe "/api/v1/contributors/contrib-1/photo"
            }
        }

        // The Kotlin server answers a successful cover/image upload with 204 No Content — NOT an
        // enveloped `{v, success, data}` body. These pin that an upload succeeds (not silently fails)
        // when the server returns 204, and pin the PUT path each upload targets.
        fun uploadApi(record: (String) -> Unit): ImageApi {
            val engine =
                MockEngine { request ->
                    record(request.url.encodedPath)
                    respond(content = byteArrayOf(), status = HttpStatusCode.NoContent)
                }
            val client = HttpClient(engine)
            val factory = mock<ApiClientFactory>()
            everySuspend { factory.getClient() } returns client
            val serverConfig = mock<ServerConfig>()
            everySuspend { serverConfig.getServerUrl() } returns ServerUrl("http://server.test")
            return ImageApi(clientFactory = factory, serverConfig = serverConfig)
        }

        test("uploadBookCover succeeds on the server's 204 No Content") {
            runTest {
                var requestedPath: String? = null
                val api = uploadApi { requestedPath = it }

                val result = api.uploadBookCover("book-1", byteArrayOf(1, 2, 3), "cover.jpg")

                result.shouldBeInstanceOf<AppResult.Success<ImageUploadResponse>>()
                requestedPath shouldBe "/api/v1/books/book-1/cover"
            }
        }

        test("uploadContributorImage succeeds on the server's 204 No Content") {
            runTest {
                var requestedPath: String? = null
                val api = uploadApi { requestedPath = it }

                val result = api.uploadContributorImage("c-1", byteArrayOf(1, 2, 3), "photo.jpg")

                result.shouldBeInstanceOf<AppResult.Success<ImageUploadResponse>>()
                requestedPath shouldBe "/api/v1/contributors/c-1/image"
            }
        }

        test("uploadSeriesCover succeeds on the server's 204 No Content") {
            runTest {
                var requestedPath: String? = null
                val api = uploadApi { requestedPath = it }

                val result = api.uploadSeriesCover("s-1", byteArrayOf(1, 2, 3), "cover.jpg")

                result.shouldBeInstanceOf<AppResult.Success<ImageUploadResponse>>()
                requestedPath shouldBe "/api/v1/series/s-1/cover"
            }
        }
    })
