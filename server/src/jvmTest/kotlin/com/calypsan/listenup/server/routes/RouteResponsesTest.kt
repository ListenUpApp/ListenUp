package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.logging.ListenUpLoggerFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.slf4j.event.Level

// BookError.NotFound → 404 (4xx); InternalError → 500 (5xx)
private val FOUR_XX_ERROR = BookError.NotFound()
private val FIVE_XX_ERROR = InternalError()

class RouteResponsesTest :
    FunSpec({

        test("a 4xx domain Failure is logged at DEBUG with code + correlationId") {
            val capture = ListenUpLoggerFactory.installTestCapture()
            try {
                testApplication {
                    routing {
                        get("/boom") {
                            call.respondAppResult<String>(AppResult.Failure(FOUR_XX_ERROR))
                        }
                    }
                    client.get("/boom")
                }
                val event =
                    capture.events.firstOrNull { it.message.contains(FOUR_XX_ERROR.code) }.shouldNotBeNull()
                event.level shouldBe Level.DEBUG
            } finally {
                ListenUpLoggerFactory.removeTestCapture()
            }
        }

        test("a 5xx Failure is logged at ERROR") {
            val capture = ListenUpLoggerFactory.installTestCapture()
            try {
                testApplication {
                    routing {
                        get("/fail") {
                            call.respondAppResult<String>(AppResult.Failure(FIVE_XX_ERROR))
                        }
                    }
                    client.get("/fail")
                }
                val event =
                    capture.events
                        .firstOrNull { it.level == Level.ERROR && it.message.contains(FIVE_XX_ERROR.code) }
                        .shouldNotBeNull()
                event.level shouldBe Level.ERROR
            } finally {
                ListenUpLoggerFactory.removeTestCapture()
            }
        }

        test("a Success response produces no error log") {
            val capture = ListenUpLoggerFactory.installTestCapture()
            try {
                testApplication {
                    routing { get("/ok") { call.respondAppResult(AppResult.Success("ok")) } }
                    client.get("/ok")
                }
                capture.events.none { it.message.contains("domain error") }.shouldBeTrue()
            } finally {
                ListenUpLoggerFactory.removeTestCapture()
            }
        }
    })
