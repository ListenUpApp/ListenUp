package com.calypsan.listenup.server.testing

import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import java.nio.file.Files
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.ktor.ext.inject

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
 * @param libraryPath when set, adds `scanner.libraryPath` so `module()` wires
 *   the scanner and books slices. Must point at an existing directory — the
 *   server skips scanner wiring for a missing/blank path. Tests that need the
 *   books domain (or a scanner) pass a fresh temp directory here.
 * @param seedProfile when set, adds `seed.profile` so `module()` installs
 *   the seed module and runs the [com.calypsan.listenup.server.seed.SeedRunner]
 *   at startup. Pass `"demo"` to exercise the demo-seed boot path.
 */
fun ApplicationTestBuilder.useIsolatedTestConfig(
    registrationPolicy: String = "OPEN",
    libraryPath: String? = null,
    seedProfile: String? = null,
) {
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
            ).apply {
                if (libraryPath != null) put("scanner.libraryPath", libraryPath)
                if (seedProfile != null) put("seed.profile", seedProfile)
            }
    }
}

/**
 * Seeds a library row with id `"test-library"` and a folder row with id
 * `"test-folder"` into the application's Koin-wired database.
 *
 * Call this inside a `testApplication { }` block after `application { module() }`,
 * so the Koin container and schema are already initialized. Tests that call
 * [com.calypsan.listenup.server.services.BookRepository.upsert] with
 * `libraryId = LibraryId("test-library")` and `folderId = FolderId("test-folder")`
 * need this to satisfy the `library_id` FK on the `books` table.
 *
 * @param folderPath the root filesystem path for the test folder (default `/tmp/test-library`).
 */
fun ApplicationTestBuilder.seedTestLibraryAndFolder(
    libraryId: String = "test-library",
    folderId: String = "test-folder",
    folderPath: String = "/tmp/test-library",
) {
    val db by application.inject<Database>()
    db.seedTestLibraryAndFolder(libraryId = libraryId, folderId = folderId, folderPath = folderPath)
}
