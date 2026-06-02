package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.api.error.AdminError
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.InviteError
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.serialization.kotlinx.json.json as serverJson

/**
 * Pinpoints the StatusPages handler's two responsibilities:
 *  - Unhandled `Throwable` becomes [InternalError(correlationId)] with HTTP 500.
 *  - Unknown paths return a structured 404 JSON body.
 *
 * Domain failures DO NOT surface here — services return [AppResult.Failure]
 * in-band; the route handler folds it. So no AuthException-specific test
 * exists (and `AuthException` itself is gone from the codebase).
 */
class AppErrorStatusPagesTest :
    FunSpec({

        test("unhandled Throwable becomes InternalError(correlationId) with HTTP 500") {
            testApplication {
                application {
                    install(ServerContentNegotiation) { serverJson() }
                    install(CallId) { generate { "test-correlation-id" } }
                    installAppErrorStatusPages()
                    routing {
                        get("/throws-bug") { error("simulated bug") }
                    }
                }
                val client =
                    createClient {
                        install(ContentNegotiation) { json() }
                    }

                val response = client.get("/throws-bug")
                response.status shouldBe HttpStatusCode.InternalServerError
                val body = response.body<AppError>()
                val internal = body.shouldBeInstanceOf<InternalError>()
                internal.correlationId shouldBe "test-correlation-id"
            }
        }

        test("TransportError maps to InternalServerError as a server-local bug guard") {
            val err: AppError = TransportError.NetworkUnavailable()
            err.toHttpStatus() shouldBe HttpStatusCode.InternalServerError
        }

        test("AudioMetadataError.UnsupportedFormat maps to 415 UnsupportedMediaType") {
            val err: AppError =
                AudioMetadataError.UnsupportedFormat(
                    pathString = "/lib/foo.wav",
                    detectedMagic = "RIFF",
                )
            err.toHttpStatus() shouldBe HttpStatusCode.UnsupportedMediaType
        }

        test("AudioMetadataError.CorruptHeader maps to 422 UnprocessableEntity") {
            val err: AppError =
                AudioMetadataError.CorruptHeader(
                    pathString = "/lib/bad.mp3",
                    format = AudioFormat.Mp3,
                    offset = 0,
                    expected = "ID3",
                )
            err.toHttpStatus() shouldBe HttpStatusCode.UnprocessableEntity
        }

        test("AudioMetadataError.TruncatedStream maps to 422 UnprocessableEntity") {
            val err: AppError =
                AudioMetadataError.TruncatedStream(
                    pathString = "/lib/short.flac",
                    format = AudioFormat.Flac,
                    expectedBytes = 1024,
                    actualBytes = 512,
                )
            err.toHttpStatus() shouldBe HttpStatusCode.UnprocessableEntity
        }

        test("AudioMetadataError.IoError maps to 500 InternalServerError") {
            val err: AppError =
                AudioMetadataError.IoError(
                    pathString = "/lib/locked.m4b",
                    ioMessage = "permission denied",
                )
            err.toHttpStatus() shouldBe HttpStatusCode.InternalServerError
        }

        test("AudioMetadataError.withCorrelationId stamps id on UnsupportedFormat") {
            val err: AppError =
                AudioMetadataError.UnsupportedFormat(
                    pathString = "/lib/foo.wav",
                    detectedMagic = "RIFF",
                )
            val stamped = err.withCorrelationId("corr-123")
            stamped.shouldBeInstanceOf<AudioMetadataError.UnsupportedFormat>()
            stamped.correlationId shouldBe "corr-123"
        }

        test("AdminError.UserNotFound maps to 404 NotFound") {
            val err: AppError = AdminError.UserNotFound()
            err.toHttpStatus() shouldBe HttpStatusCode.NotFound
        }

        test("AdminError.CannotDemoteLastAdmin maps to 409 Conflict") {
            val err: AppError = AdminError.CannotDemoteLastAdmin()
            err.toHttpStatus() shouldBe HttpStatusCode.Conflict
        }

        test("AdminError.InvalidInput maps to 400 BadRequest") {
            val err: AppError = AdminError.InvalidInput()
            err.toHttpStatus() shouldBe HttpStatusCode.BadRequest
        }

        test("AdminError.withCorrelationId stamps id on CannotModifyRoot") {
            val err: AppError = AdminError.CannotModifyRoot()
            val stamped = err.withCorrelationId("corr-admin")
            stamped.shouldBeInstanceOf<AdminError.CannotModifyRoot>()
            stamped.correlationId shouldBe "corr-admin"
        }

        test("InviteError.NotFound maps to 404 NotFound") {
            val err: AppError = InviteError.NotFound()
            err.toHttpStatus() shouldBe HttpStatusCode.NotFound
        }

        test("InviteError.Expired maps to 409 Conflict") {
            val err: AppError = InviteError.Expired()
            err.toHttpStatus() shouldBe HttpStatusCode.Conflict
        }

        test("InviteError.InvalidInput maps to 400 BadRequest") {
            val err: AppError = InviteError.InvalidInput()
            err.toHttpStatus() shouldBe HttpStatusCode.BadRequest
        }

        test("InviteError.withCorrelationId stamps id on AlreadyClaimed") {
            val err: AppError = InviteError.AlreadyClaimed()
            val stamped = err.withCorrelationId("corr-invite")
            stamped.shouldBeInstanceOf<InviteError.AlreadyClaimed>()
            stamped.correlationId shouldBe "corr-invite"
        }

        test("ProfileError.WrongPassword maps to 422 UnprocessableEntity") {
            val err: AppError = ProfileError.WrongPassword()
            err.toHttpStatus() shouldBe HttpStatusCode.UnprocessableEntity
        }

        test("ProfileError.InvalidImage maps to 422 UnprocessableEntity") {
            val err: AppError = ProfileError.InvalidImage()
            err.toHttpStatus() shouldBe HttpStatusCode.UnprocessableEntity
        }

        test("unknown paths return structured JSON 404") {
            testApplication {
                application {
                    install(ServerContentNegotiation) { serverJson() }
                    install(CallId) { generate { "any" } }
                    installAppErrorStatusPages()
                    routing {
                        get("/exists") { /* default 200 reply */ }
                    }
                }
                val client = createClient { install(ContentNegotiation) { json() } }

                val response = client.get("/this-does-not-exist")
                response.status shouldBe HttpStatusCode.NotFound
            }
        }
    })
