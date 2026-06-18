package com.calypsan.listenup.web.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.cookie
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class CsrfRoutesTest :
    FunSpec({
        test("a mutating request without the CSRF header is rejected (403)") {
            testApplication {
                application {
                    routing {
                        route("/guarded") {
                            install(CSRF, webCsrfConfig)
                            post { call.respondText("ok") }
                        }
                    }
                }
                client.post("/guarded").status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("a mutating request with a matching header+cookie passes") {
            testApplication {
                application {
                    routing {
                        route("/guarded") {
                            install(CSRF, webCsrfConfig)
                            post { call.respondText("ok") }
                        }
                    }
                }
                val response =
                    client.post("/guarded") {
                        cookie("lu_csrf", "tok-1")
                        header("X-CSRF-Token", "tok-1")
                    }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("a mutating request whose header does not match the cookie is rejected") {
            testApplication {
                application {
                    routing {
                        route("/guarded") {
                            install(CSRF, webCsrfConfig)
                            post { call.respondText("ok") }
                        }
                    }
                }
                val response =
                    client.post("/guarded") {
                        cookie("lu_csrf", "tok-1")
                        header("X-CSRF-Token", "tok-2")
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }
    })
