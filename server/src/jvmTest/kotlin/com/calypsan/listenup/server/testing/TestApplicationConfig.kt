package com.calypsan.listenup.server.testing

import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import java.nio.file.Files
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
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
 *   Pass `null` to omit the key entirely and exercise the server's own default
 *   for a fresh/unconfigured instance.
 * @param libraryPath when set, adds `scanner.libraryPath` so `module()` wires
 *   the scanner and books slices. Must point at an existing directory — the
 *   server skips scanner wiring for a missing/blank path. Tests that need the
 *   books domain (or a scanner) pass a fresh temp directory here.
 * @param seedProfile when set, adds `seed.profile` so `module()` installs
 *   the seed module and runs the [com.calypsan.listenup.server.seed.SeedRunner]
 *   at startup. Pass `"demo"` to exercise the demo-seed boot path.
 * @param homeDir when set, adds `listenup.home` so `module()` resolves the
 *   image home (covers / contributor photos / series covers) to this directory
 *   instead of `$LISTENUP_HOME`/`~/ListenUp`. Tests serving metadata images pass
 *   a fresh temp directory here.
 * @param rescanOnStartup when `false`, sets `scan.rescanOnStartup` so the
 *   bootstrap skips the startup library scan. Defaults to `true` (production
 *   behaviour). Tests that write fixtures after boot pair this with
 *   `watchEnabled = false` as belt-and-suspenders against any boot-time scan.
 * @param watchEnabled when `true`, leaves the real-time file-system watcher
 *   mounted; defaults to `false`. Real-time watching is pure overhead for the
 *   ~all tests that don't assert on it, and a fixture write into the library
 *   root after boot otherwise triggers a scan that races the test's own seed
 *   (the flake `AudioRoutesTest`/`BookCoverRouteTest` hit). Tests that genuinely
 *   exercise the watcher (e.g. the watcher-unmount path) pass `true`. Same
 *   rationale as the `mdns.enabled = false` default above.
 */
fun ApplicationTestBuilder.useIsolatedTestConfig(
    registrationPolicy: String? = "OPEN",
    libraryPath: String? = null,
    seedProfile: String? = null,
    homeDir: String? = null,
    rescanOnStartup: Boolean = true,
    watchEnabled: Boolean = false,
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
                // Disable the lost-response reuse-grace window so integration tests can pin the
                // family-revoke path against a real Clock.System (an immediate replay would otherwise
                // fall inside the grace window and rotate again). Unit tests exercise the grace
                // behaviour directly with a controllable clock.
                "auth.refreshReuseGraceSeconds" to "0",
                // No test should bind multicast sockets or run an mDNS receive loop — pure overhead
                // and a source of cross-test load that flakes the firehose timeout budget.
                "mdns.enabled" to "false",
                // Real-time file-system watching is off by default: it's overhead for tests that
                // don't assert on it and a fixture-write-vs-seed race for those that do.
                "scanner.watchEnabled" to watchEnabled.toString(),
                "scan.rescanOnStartup" to rescanOnStartup.toString(),
            ).apply {
                if (registrationPolicy != null) put("registration.policy", registrationPolicy)
                if (libraryPath != null) put("scanner.libraryPath", libraryPath)
                if (seedProfile != null) put("seed.profile", seedProfile)
                if (homeDir != null) put("listenup.home", homeDir)
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
    val sql by application.inject<ListenUpDatabase>()
    sql.seedTestLibraryAndFolder(libraryId = libraryId, folderId = folderId, folderPath = folderPath)
}
