package com.calypsan.listenup.server.testing

import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import java.nio.file.Files

/**
 * Wires a per-test application config matching `application.conf`'s shape but
 * pointing at a fresh in-process SQLite file. Each call yields an isolated DB.
 *
 * `testApplication { environment { config = ... } }` defaults to an empty
 * `MapApplicationConfig` — calling `module()` without this would crash on the
 * first `config.property("...").getString()` lookup.
 *
 * @param registrationPolicy override the registration policy bucket (`OPEN`,
 *   `APPROVAL_QUEUE`, `CLOSED`). Tests covering policy branching pass it here.
 */
fun ApplicationTestBuilder.useIsolatedTestConfig(registrationPolicy: String = "OPEN") {
    val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
    environment {
        config =
            MapApplicationConfig(
                "database.jdbcUrl" to "jdbc:sqlite:${tmp.absolutePath}",
                "auth.refreshPepper" to "x".repeat(32),
                "jwt.secret" to "x".repeat(32),
                "jwt.issuer" to "listenup",
                "jwt.audience" to "listenup-client",
                "registration.policy" to registrationPolicy,
            )
    }
}
