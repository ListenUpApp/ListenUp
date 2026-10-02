package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.server.hardcover.FakeHardcoverCatalog
import com.calypsan.listenup.server.hardcover.HardcoverMetadataSource
import com.calypsan.listenup.server.logging.ListenUpLoggerFactory
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.publicAuthService
import com.calypsan.listenup.server.testing.shouldFailWith
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.get as koinGet

/** Recognisable fakes: neither is a real token, and neither may appear in any log line. */
private const val GOOD = "hc_secret_good_token_7f3a9c"
private const val WRONG = "hc_secret_wrong_token_0b1d2e"

/**
 * The admin's Hardcover API token never reaches a log (#1542). The real server boots against a fake
 * Hardcover with every logger forced to DEBUG; the token is refused, saved, read with, and finally
 * rejected by Hardcover — through the real RPC socket — and no captured line carries it, in its message,
 * its MDC or its stack trace.
 */
class HardcoverApiTokenSecrecyTest :
    FunSpec({
        test("the admin's API token is never logged: refused, saved, read with, or rejected") {
            val hardcover =
                FakeHardcoverCatalog().apply {
                    accounts = mapOf(GOOD to "simon")
                    rejected = setOf(WRONG)
                }
            val fake =
                embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                    routing {
                        post("/v1/graphql") {
                            val bearer = call.request.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer ")
                            val (status, body) = hardcover.handle(call.receiveText(), bearer)
                            call.respondText(body, ContentType.Application.Json, status)
                        }
                    }
                }.start(wait = false)
            // installTestCapture() forces DEBUG on every logger JVM-wide; safe because :server:jvmTest runs
            // specs sequentially.
            val capture = ListenUpLoggerFactory.installTestCapture()
            try {
                val port = runBlocking { fake.engine.resolvedConnectors() }.first().port
                testApplication {
                    useIsolatedTestConfig(rescanOnStartup = false, hardcoverApiBaseUrl = "http://127.0.0.1:$port")
                    lateinit var app: Application
                    application {
                        module()
                        app = this
                    }
                    startApplication()
                    val session = publicAuthService().setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                    val admin = authedService<AdminSettingsService>(session.accessToken.value)

                    admin.setHardcoverApiToken(WRONG).shouldFailWith<HardcoverError.TokenRejected>()
                    admin.setHardcoverApiToken(GOOD).shouldSucceed().apiToken.shouldBeInstanceOf<HardcoverApiTokenStatus.Saved>()
                    admin.getHardcoverSource().shouldSucceed().toString() shouldNotContain GOOD

                    hardcover.rejected = setOf(WRONG, GOOD)
                    app.koinGet<HardcoverMetadataSource>().getGenres(BookIdentity(asin = "B0SECRET01", title = ""), MetadataLocale.DEFAULT)

                    admin.getHardcoverSource().shouldSucceed().apiToken shouldBe HardcoverApiTokenStatus.Rejected("simon")
                }
            } finally {
                ListenUpLoggerFactory.removeTestCapture()
                fake.stop()
            }

            val events = capture.events
            events.any { "rejected the admin API token" in it.message } shouldBe true
            events.forEach { event ->
                val seen = event.message + " " + event.mdc.values.joinToString(" ") + " " + (event.throwable?.stackTraceToString() ?: "")
                seen shouldNotContain GOOD
                seen shouldNotContain WRONG
            }
        }
    })
