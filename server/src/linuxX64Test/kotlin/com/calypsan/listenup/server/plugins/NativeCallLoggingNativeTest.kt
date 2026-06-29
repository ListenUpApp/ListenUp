package com.calypsan.listenup.server.plugins

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Native runtime proof for the linuxX64 [installCallLogging] actual: it installs on the real CIO
 * engine and a request flows through the logging interceptor without error, returning 200. The
 * interceptor's emitted line is not asserted (capturing the native logging appender's stdout is not
 * practical) — the contract under test is "installs + serves through the interceptor on native".
 */
class NativeCallLoggingNativeTest {
    @Test
    fun installsAndServesARequestThroughTheLoggingInterceptor(): Unit =
        runBlocking {
            val server =
                embeddedServer(
                    factory = CIO,
                    configure = { connectors.add(EngineConnectorBuilder().apply { port = 0 }) },
                ) {
                    installCallId()
                    installCallLogging()
                    routing { get("/ping") { call.respondText("pong") } }
                }
            server.start(wait = false)
            val client = HttpClient(ClientCIO)
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val response = client.get("http://127.0.0.1:$port/ping")
                response.status shouldBe HttpStatusCode.OK
            } finally {
                client.close()
                server.stop(0, 0)
            }
        }
}
