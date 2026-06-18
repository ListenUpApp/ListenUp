package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.server.module
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import java.net.ServerSocket
import java.nio.file.Files

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

class WebAuthEndToEndTest :
    FunSpec({
        test("web setup → home, with login round-tripping through the real loopback REST") {
            val port = freePort()
            val tmpDb = Files.createTempFile("listenup-web-e2e-", ".db").toFile().apply { deleteOnExit() }
            val tmpHome = Files.createTempDirectory("listenup-web-e2e-home-").toFile().apply { deleteOnExit() }
            val env =
                applicationEnvironment {
                    config =
                        MapApplicationConfig(
                            "database.jdbcUrl" to "jdbc:sqlite:${tmpDb.absolutePath}",
                            "auth.refreshPepper" to "x".repeat(32),
                            "jwt.secret" to "x".repeat(32),
                            "jwt.issuer" to "listenup",
                            "jwt.audience" to "listenup-client",
                            "registration.policy" to "OPEN",
                            "mdns.enabled" to "false",
                            "scanner.watchEnabled" to "false",
                            "scan.rescanOnStartup" to "false",
                            "listenup.home" to tmpHome.absolutePath,
                            "ktor.deployment.port" to port.toString(),
                        )
                }
            val server =
                embeddedServer(
                    factory = ServerCIO,
                    environment = env,
                    configure = {
                        connectors.add(
                            EngineConnectorBuilder().apply {
                                host = "127.0.0.1"
                                this.port = port
                            },
                        )
                    },
                ) {
                    module()
                }
            server.start(wait = false)
            val baseUrl = "http://127.0.0.1:$port"
            val client =
                HttpClient(CIO) {
                    install(HttpCookies)
                    followRedirects = false
                    defaultRequest { url(baseUrl) }
                }
            try {
                // Fresh server → GET / redirects to /setup.
                client.get("/").headers["Location"] shouldBe "/setup"

                // Set up the root account through the BFF (real REST + SQLite).
                client.get("/setup")
                val token = client.cookies(baseUrl).first { it.name == "lu_csrf" }.value
                val setup =
                    client.submitForm(
                        url = "/setup",
                        formParameters =
                            parameters {
                                append("email", "root@x")
                                append("displayName", "Root")
                                append("password", "password1")
                            },
                    ) { header("X-CSRF-Token", token) }
                setup.headers["HX-Redirect"] shouldBe "/"

                // The session cookie now authenticates the home placeholder.
                val home = client.get("/")
                home.bodyAsText() shouldContain "Your library, in the browser."
            } finally {
                client.close()
                @Suppress("MagicNumber")
                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            }
        }
    })
