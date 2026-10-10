package com.calypsan.listenup.client.data.local.db.migration

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.room3.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking

/**
 * Drift guard for the committed Room schema baseline (currently **v23** — the Room 3 baseline plus
 * the v1 → v2 volume-boost migration, the v2 → v3 `books.normalizationGainDb` migration, the
 * v3 → v4 per-user permission flags, the v4 → v5 presence-cache columns, the v5 → v6 numeric
 * `book_series.sequence`, the v6 → v7 `notifications` inbox table, the v7 → v8 two-tier
 * chapter-grouping columns, the v8 → v9 `book_ratings` table, the v9 → v10
 * `book_external_ratings` table, the data-only v10 → v11 `UNKNOWN`-source repair, the v11 → v12
 * `book_readership.hardcoverFinishesJson` column, the v12 → v13 drop of the inert `canShare` columns,
 * the v13 → v14 `book_external_ratings.fetchedAt` column, the v14 → v15
 * `book_readership.finishesAlsoOnHardcoverJson` column, the v15 → v16 series-hierarchy columns,
 * the v16 → v17 `libraries.metadataRegion` column, the v17 → v18 `books.lastMatch` column, the
 * v18 → v19 `canCurateLibrary` columns, the v19 → v20 Story World entities table and flags, and the
 * v20 → v21 reading-order tables and columns, the v21 → v22 Story World event tables, and the v22 → v23
 * `book_ratings.source` column).
 *
 * The current authoritative baseline is `schemas/…/ListenUpDatabase/23.json`. Nothing else asserts
 * that this JSON still matches the compiled `@Entity` set: Room's Gradle plugin *re-exports* the
 * JSON on build instead of failing, so an entity edit that forgets to commit the regenerated
 * `23.json` — or a JSON edit that doesn't match the entities — is invisible to CI.
 *
 * This test closes that gap. It creates a database whose schema (and stored identity hash)
 * comes from the committed baseline JSON, then reopens the same file with the real compiled
 * [ListenUpDatabase] WITHOUT the destructive fallback the platform modules use. Room validates
 * the stored identity hash against the compiled schema on first connection use, so any drift
 * between the JSON and the entities fails this test loudly.
 *
 * A schema change is landed by bumping the DB version, shipping a hand-written migration (the
 * destructive fallback is gone — the local DB holds the unsynced outbox), and re-exporting the
 * baseline — this guard is then pinned to the new latest version. Migration-path validation
 * itself lives in [SchemaMigrationSmokeTest]; this test only pins baseline↔entity identity.
 */
class SchemaBaselineDriftTest :
    FunSpec({
        test("compiled ListenUpDatabase opens a database created from the committed 23.json baseline") {
            // Resolve the exported-schema directory the same way the shared helper does:
            // Gradle runs :app:sharedLogic:jvmTest with the module root as working directory,
            // so `schemas` points at the Room-plugin export folder.
            val schemaDirectory: Path = Paths.get("schemas").toAbsolutePath()
            check(Files.isDirectory(schemaDirectory)) {
                "Room schemas directory not found at $schemaDirectory. " +
                    "Run `./gradlew :app:sharedLogic:kspKotlinJvm` to regenerate `app/sharedLogic/schemas/`."
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
                // Create the schema in `databasePath` FROM the committed 23.json (this also
                // writes the JSON's identity hash into room_master_table), then release it.
                helper.createDatabase(version = 23).close()

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
                        "committed 23.json no longer matches the compiled @Entity schema — " +
                            "regenerate app/sharedLogic/schemas/…/ListenUpDatabase/23.json " +
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
