package com.calypsan.listenup.server.cover

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.imaging.PixelBuffer
import com.calypsan.listenup.server.imaging.encodeJpeg
import com.calypsan.listenup.server.imaging.hexBytes
import com.calypsan.listenup.server.imaging.packPixel
import com.calypsan.listenup.server.imaging.parseJpegSegments
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.testing.filesystemCoverBook
import com.calypsan.listenup.server.testing.publicAuthService
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * `?w=` on `GET /api/v1/books/{id}/cover` — the whole arc's consumer-facing surface.
 *
 * Boots the real `Application.module()`, so the route, the responder, the derivative store and the
 * imaging codec all participate exactly as they do in production. The seeded cover is a genuine
 * 1200px JPEG written by our own encoder, because a 300px rung needs a source of at least 4× that
 * width for the decoder's reductions to reach it — a stub JPEG would decline and every assertion
 * would pass for the wrong reason.
 *
 * ⛔ **The guarantee that matters most is the one with no `?w=` at all**: native clients must keep
 * receiving byte-for-byte what they receive today.
 */
class CoverWidthTest :
    FunSpec({

        suspend fun ApplicationTestBuilder.mintAccessToken(): String {
            publicAuthService().setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
            return publicAuthService()
                .login(LoginRequest("root@x", "x".repeat(8)))
                .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                .data
                .accessToken
                .value
        }

        /**
         * Boots a server whose book `b1` has [coverBytes] on disk, and runs [assertions] against a
         * cover request carrying [query].
         */
        suspend fun servingCover(
            coverBytes: ByteArray,
            query: String,
            ifNoneMatch: String? = null,
            assertions: suspend (HttpResponse) -> Unit,
        ) {
            val libraryRoot = Files.createTempDirectory("listenup-cover-width-")
            val home = Files.createTempDirectory("listenup-cover-width-home-")
            try {
                testApplication {
                    useIsolatedTestConfig(
                        libraryPath = libraryRoot.toString(),
                        homeDir = home.toString(),
                    )
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = mintAccessToken()
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())

                    val bookDir = Files.createDirectories(libraryRoot.resolve("books/b1"))
                    Files.write(bookDir.resolve("cover.jpg"), coverBytes)

                    val repo by application.inject<BookRepository>()
                    repo.upsert(filesystemCoverBook(id = "b1", hash = HASH))

                    assertions(
                        client.get("/api/v1/books/b1/cover$query") {
                            bearerAuth(token)
                            ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
                        },
                    )
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
                home.toFile().deleteRecursively()
            }
        }

        test("?w=300 serves a 300px derivative tagged as its own variant") {
            servingCover(wideJpeg(), "?w=$RUNG") { response ->
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ETag] shouldBe "\"$HASH@$RUNG\""
                response.headers[HttpHeaders.ContentType] shouldBe "image/jpeg"

                val body = response.bodyAsBytes()
                parseJpegSegments(body).shouldNotBeNull().frame.width shouldBe RUNG
                body.size shouldBeLessThan wideJpeg().size
            }
        }

        // The native-client guarantee: an unparameterised request is the endpoint it always was.
        test("no width serves exactly today's bytes under today's ETag") {
            val source = wideJpeg()
            servingCover(source, "") { response ->
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ETag] shouldBe "\"$HASH\""
                response.bodyAsBytes().toList() shouldBe source.toList()
            }
        }

        test("a width past the top of the ladder serves the original") {
            val source = wideJpeg()
            servingCover(source, "?w=5000") { response ->
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ETag] shouldBe "\"$HASH\""
                response.bodyAsBytes().toList() shouldBe source.toList()
            }
        }

        test("a width that is not a number serves the original") {
            val source = wideJpeg()
            servingCover(source, "?w=wide") { response ->
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsBytes().toList() shouldBe source.toList()
            }
        }

        // A cover the codec declines keeps serving its original — the arc's standing rule. It also
        // must not borrow the variant ETag, or a later codec that CAN derive it would stay invisible.
        test("a cover the decoder declines serves the original under the original ETag") {
            val source = undecodableJpeg()
            servingCover(source, "?w=$RUNG") { response ->
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ETag] shouldBe "\"$HASH\""
                response.bodyAsBytes().toList() shouldBe source.toList()
            }
        }

        test("If-None-Match on the variant tag is a 304") {
            servingCover(wideJpeg(), "?w=$RUNG", ifNoneMatch = "\"$HASH@$RUNG\"") { response ->
                response.status shouldBe HttpStatusCode.NotModified
                response.bodyAsBytes().size shouldBe 0
            }
        }
    })

/**
 * A real 1200px JPEG — four quadrants, so a derivative that is blank or transposed cannot pass.
 * 1200 is 4× the [RUNG], the smallest source the decoder's reductions can serve that rung from.
 */
private fun wideJpeg(): ByteArray {
    val size = RUNG * 4
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val left = x < size / 2
            val top = y < size / 2
            pixels[y * size + x] =
                when {
                    top && left -> packPixel(255, 200, 40, 40)
                    top -> packPixel(255, 40, 40, 200)
                    left -> packPixel(255, 40, 180, 40)
                    else -> packPixel(255, 230, 230, 230)
                }
        }
    }
    return encodeJpeg(PixelBuffer(size, size, pixels), quality = 90)
}

/**
 * JPEG magic over nothing the decoder can use — the shape of the corpus's undecodable covers.
 * Written as hex rather than `byteArrayOf`, which the formatter reflows into an over-long line.
 */
private fun undecodableJpeg(): ByteArray = hexBytes("FFD8FFE000104A46")

private const val RUNG = 300
private const val HASH = "abc123"
