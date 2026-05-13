package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.sync.syncRoutes
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/**
 * Test scope exposing the JSON-capable [client] and [tagRepo] to test bodies.
 * Decouples test code from [io.ktor.server.testing.ApplicationTestBuilder] so
 * the two overloads of [withTestApplication] are unambiguous.
 */
data class SyncTestScope(
    val client: HttpClient,
    val tagRepo: TagRepository,
)

/**
 * Minimal Ktor test harness for Sync Foundation route tests.
 *
 * Constructs an isolated SQLite database (same temp-file pattern as
 * [withInMemoryDatabase]), wires a small Koin module with `Database`,
 * `ChangeBus`, and `TagRepository`, installs `ContentNegotiation` and `SSE`,
 * and mounts [syncRoutes]. Provides a JSON-capable [SyncTestScope.client] so
 * `response.body<Page<Tag>>()` round-trips using [contractJson] on both sides.
 *
 * Used by Tasks 15-18. Additional domain repositories can be added to the
 * Koin module when later tasks introduce them.
 */
internal fun withTestApplication(
    heartbeatIntervalMillis: Long? = null,
    block: suspend SyncTestScope.() -> Unit,
) {
    testApplication {
        val tmp =
            Files.createTempFile("listenup-sync-test-", ".db").toFile().apply { deleteOnExit() }
        val db =
            DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
        val bus = ChangeBus()
        val registry = SyncRegistry()
        val tagRepo = TagRepository(db, bus, registry)

        application {
            install(ServerContentNegotiation) { json(contractJson) }
            install(SSE)
            install(Koin) {
                modules(
                    module {
                        single { db }
                        single { bus }
                        single { registry }
                        single(createdAtStart = true) { tagRepo }
                    },
                )
            }
            routing {
                if (heartbeatIntervalMillis != null) {
                    syncRoutes(heartbeatIntervalMillis = heartbeatIntervalMillis)
                } else {
                    syncRoutes()
                }
            }
        }

        val jsonClient =
            createClient {
                install(ContentNegotiation) { json(contractJson) }
                install(io.ktor.client.plugins.sse.SSE)
            }

        SyncTestScope(client = jsonClient, tagRepo = tagRepo).block()
    }
}
