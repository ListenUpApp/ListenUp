package com.calypsan.listenup.server

import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.server.db.DatabaseHandle
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import com.calypsan.listenup.api.contractJson
import org.koin.ktor.ext.inject

/**
 * Proves the application releases its connection pool on stop. Before the fix the
 * Hikari pool (and its threads + SQLite file handles) leaked past every
 * `testApplication` teardown — a CI thread dump showed ~71 leaked `HikariPool-*`
 * threads in one JVM worker. The bootstrap (libraryPath set) also mounts a file
 * watcher, so this exercises the watcher-unmount path too (no `Failed to destroy
 * application instance` timeout).
 */
class GracefulShutdownTest :
    FunSpec({
        test("application stop closes the connection pool") {
            val libraryRoot = Files.createTempDirectory("listenup-shutdown-")
            lateinit var handle: DatabaseHandle
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString(), watchEnabled = true)
                    application { module() }
                    val client =
                        createClient { install(ContentNegotiation) { json(contractJson) } }

                    // Trigger module start + the library bootstrap (which mounts a watcher).
                    client.post("/api/v1/auth/setup") {
                        contentType(ContentType.Application.Json)
                        setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
                    }

                    handle = application.inject<DatabaseHandle>().value
                    handle.isPoolClosed() shouldBe false
                }
                // testApplication block exit fires ApplicationStopped → graceful shutdown ran.
                handle.isPoolClosed() shouldBe true
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })
