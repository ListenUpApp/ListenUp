package com.calypsan.listenup.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.server.cio.CIO
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Hard ceiling on the whole restart, so a wedge fails as this named test rather than a step timeout. */
private val RESTART_TIMEOUT = 60.seconds

private const val REQUEST_TIMEOUT_MS = 5_000L

/** HTTP/1.1 line ending. */
private const val CRLF = "\r\n"

/**
 * A server restarted on the port it just left must come back up. Stopping a server that still holds
 * client connections leaves them in TIME_WAIT on its port for about a minute, and on Kotlin/Native
 * Ktor CIO binds without `SO_REUSEADDR` unless told to — so the rebind failed with EADDRINUSE and the
 * uncaught bind error aborted the process. Every client reconnects the instant a server goes away, so
 * any restart of a live server hit it. (The JVM defaults `SO_REUSEADDR` on, which is why no JVM test
 * ever saw it.)
 */
class NativeServerRestartTest :
    FunSpec({
        test("a server restarted while its old connections linger in TIME_WAIT binds the same port") {
            val client =
                HttpClient(ClientCIO) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = REQUEST_TIMEOUT_MS
                        connectTimeoutMillis = REQUEST_TIMEOUT_MS
                        socketTimeoutMillis = REQUEST_TIMEOUT_MS
                    }
                }
            try {
                withTimeout(RESTART_TIMEOUT) {
                    val first = pingServer(port = 0)
                    first.start(wait = false)
                    val port =
                        first.engine
                            .resolvedConnectors()
                            .first()
                            .port
                    awaitPong(client, port).bodyAsText() shouldBe "pong"
                    // A connection still open when the server stops is closed from the server's side,
                    // which is what parks it in TIME_WAIT on `port` — exactly what a connected app's
                    // long-lived sockets do. (Ktor's client closes its own connections, so it can't.)
                    val selector = SelectorManager()
                    val lingering = holdOpenConnection(selector, port)
                    first.stop(0, 0)
                    lingering.close()
                    selector.close()

                    val second = pingServer(port = port)
                    second.start(wait = false)
                    try {
                        awaitPong(client, port).bodyAsText() shouldBe "pong"
                    } finally {
                        second.stop(0, 0)
                    }
                }
            } finally {
                client.close()
            }
        }
    })

/** A bare server on [port], configured exactly as [main] configures the real one. */
private fun pingServer(port: Int) =
    embeddedServer(factory = CIO, configure = { listenOn(port) }) {
        routing { get("/ping") { call.respondText("pong") } }
    }

/**
 * Opens a raw keep-alive connection to [port] on [selector] (which the caller closes), completes one request on it, and leaves it open — the
 * shape of a connected app's socket at the moment its server stops.
 */
private suspend fun holdOpenConnection(
    selector: SelectorManager,
    port: Int,
): Socket {
    val socket = aSocket(selector).tcp().connect("127.0.0.1", port)
    val output = socket.openWriteChannel(autoFlush = true)
    val request = listOf("GET /ping HTTP/1.1", "Host: 127.0.0.1", "Connection: keep-alive", "", "")
    output.writeStringUtf8(request.joinToString(CRLF))
    val input = socket.openReadChannel()
    while (input.readUTF8Line()?.isNotEmpty() == true) {
        // Read the status line and headers; the connection stays open afterwards.
    }
    return socket
}

/** Polls `/ping` until the server answers; bounded from outside by [RESTART_TIMEOUT]. */
private suspend fun awaitPong(
    client: HttpClient,
    port: Int,
): HttpResponse {
    while (true) {
        runCatching { client.get("http://127.0.0.1:$port/ping") }.getOrNull()?.let { return it }
        delay(100)
    }
}
