package com.calypsan.listenup.client.data.local.db.migration

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.room.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking

/**
 * Drift guard for the committed Room schema baseline (currently **v4** — the v3 export with the
 * dormant BlurHash columns dropped).
 *
 * PR #1060 collapsed the client migration chain to a single committed export; the current
 * authoritative baseline is `schemas/…/ListenUpDatabase/4.json`. Nothing else asserts that
 * this JSON still matches the compiled `@Entity` set: Room's Gradle plugin *re-exports* the
 * JSON on build instead of failing, so an entity edit that forgets to commit the regenerated
 * `2.json` — or a JSON edit that doesn't match the entities — is invisible to CI.
 *
 * This test closes that gap. It creates a database whose schema (and stored identity hash)
 * comes from the committed baseline JSON, then reopens the same file with the real compiled
 * [ListenUpDatabase] WITHOUT the destructive fallback the platform modules use. Room validates
 * the stored identity hash against the compiled schema on first connection use, so any drift
 * between the JSON and the entities fails this test loudly.
 *
 * The pre-launch policy is `fallbackToDestructiveMigration(true)` with no hand-written
 * migration chain, so a schema change is landed by bumping the DB version and re-exporting the
 * baseline — this guard is then pinned to the new latest version. When a real migration chain
 * begins (fallback flipped to `false` before launch), this evolves into the migration-and-validate
 * suite the [SchemaMigrationSmokeTest] KDoc promises; the temp-file + reopen pattern here is the
 * scaffold for it.
 */
class SchemaBaselineDriftTest :
    FunSpec({
        test("compiled ListenUpDatabase opens a database created from the committed 4.json baseline") {
            // Resolve the exported-schema directory the same way the shared helper does:
            // Gradle runs :sharedLogic:jvmTest with the module root as working directory,
            // so `schemas` points at the Room-plugin export folder.
            val schemaDirectory: Path = Paths.get("schemas").toAbsolutePath()
            check(Files.isDirectory(schemaDirectory)) {
                "Room schemas directory not found at $schemaDirectory. " +
                    "Run `./gradlew :sharedLogic:kspKotlinJvm` to regenerate `sharedLogic/schemas/`."
            }

            // We must hold the temp-file path ourselves so the same file can be reopened
            // with the real database class, so we construct MigrationTestHelper directly
            // rather than through createMigrationTestHelper() (which hides its temp file).
            val databasePath: Path = Files.createTempFile("listenup-schema-baseline-test", ".db")
            Files.deleteIfExists(databasePath)
            databasePath.toFile().deleteOnExit()

            val helper =
                MigrationTestHelper(
                    schemaDirectoryPath = schemaDirectory,
                    databasePath = databasePath,
                    driver = BundledSQLiteDriver(),
                    databaseClass = ListenUpDatabase::class,
                )

            try {
                // Create the schema in `databasePath` FROM the committed 4.json (this also
                // writes the JSON's identity hash into room_master_table), then release it.
                helper.createDatabase(version = 4).close()

                // Reopen the SAME file with the real compiled database — deliberately WITHOUT
                // fallbackToDestructiveMigration, so Room's identity-hash validation runs
                // instead of silently nuking on mismatch.
                val database =
                    Room
                        .databaseBuilder<ListenUpDatabase>(name = databasePath.toString())
                        .setDriver(BundledSQLiteDriver())
                        .build()

                try {
                    withClue(
                        "committed 4.json no longer matches the compiled @Entity schema — " +
                            "regenerate sharedLogic/schemas/…/ListenUpDatabase/4.json " +
                            "(the build re-exports it) and commit the diff",
                    ) {
                        // First connection use forces Room to open and validate the stored
                        // identity hash against the compiled schema.
                        runBlocking {
                            database.useReaderConnection { }
                        }
                    }
                } finally {
                    database.close()
                }
            } finally {
                // Close the helper's managed connections. `finished(Description?)` is a
                // protected method on a final class, so reflection is the only path in —
                // the shared wrapper's close() does the same.
                val finished =
                    MigrationTestHelper::class.java.getDeclaredMethod(
                        "finished",
                        org.junit.runner.Description::class.java,
                    )
                finished.isAccessible = true
                finished.invoke(helper, null)
            }
        }
    })
