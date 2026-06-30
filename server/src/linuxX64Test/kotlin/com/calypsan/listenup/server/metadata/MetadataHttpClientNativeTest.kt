package com.calypsan.listenup.server.metadata

import com.calypsan.listenup.server.di.metadataHttpClient
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
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
 * Native capability proof for the metadata HTTP client seam (`metadataHttpClient`): on Kotlin/Native
 * it builds a Curl-engine client (libcurl) with ContentNegotiation — the engine `MetadataModule`
 * wires for the Audible/iTunes scrapers (CIO's TLS is unsupported on K/N). This pins that the native
 * metadata client can perform a GET **and deserialize a JSON body via ContentNegotiation** — the exact
 * path the scrapers rely on. Real outbound HTTPS is verified manually via `runNative` (CI has no
 * outbound network), so this exercises the request + JSON-deserialize seam over loopback HTTP.
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
                metadataHttpClient {
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
