package com.calypsan.listenup.server.metadata

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test

/**
 * Native capability proof for the metadata HTTP client (Phase 5-4c): `MetadataModule` wires an
 * `HttpClient(CIO)` with ContentNegotiation to call Audible/iTunes. This pins that a Kotlin/Native
 * CIO client can perform a GET **and deserialize a JSON body via ContentNegotiation** — the exact
 * path the scrapers rely on (#913's multipart test proved client request/response but not the
 * client-side JSON ContentNegotiation seam).
 *
 * `kotlin.test.@Test` + `runBlocking`, `Unit` body, real `embeddedServer(CIO)` over an ephemeral port.
 */
class MetadataHttpClientNativeTest {
    @Serializable
    @SerialName("CatalogItem")
    private data class CatalogItem(
        val asin: String,
        val title: String,
    )

    private val lenientJson = Json { ignoreUnknownKeys = true }

    @Test
    fun nativeCioClientGetsAndDeserializesJson(): Unit =
        runBlocking {
            val server =
                embeddedServer(CIO, port = 0) {
                    install(ServerContentNegotiation) { json(lenientJson) }
                    routing {
                        get("/catalog") {
                            call.respond(CatalogItem(asin = "B0CXYZ", title = "The Way of Kings"))
                        }
                    }
                }
            server.start(wait = false)
            val client =
                HttpClient(ClientCIO) {
                    install(ClientContentNegotiation) { json(lenientJson) }
                }
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val item: CatalogItem = client.get("http://127.0.0.1:$port/catalog").body()
                item.asin shouldBe "B0CXYZ"
                item.title shouldBe "The Way of Kings"
            } finally {
                client.close()
                server.stop(0, 0)
            }
        }
}
