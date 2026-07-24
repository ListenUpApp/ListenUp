package com.calypsan.listenup.client.data.local.db.migration

import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Guards MIG-1: the platform `DatabaseModule` builders must never call
 * `fallbackToDestructiveMigration`. That call SILENTLY wipes and recreates the local DB on any
 * schema mismatch — and the local DB holds the unsynced outbox (`PendingOperationV2Entity`: queued
 * playback positions, listening history, offline edits not yet pushed) plus `syncedAt`-pending rows.
 * The "re-syncs from the server" justification does not cover that data, because it never reached
 * the server. The non-destructive policy makes a missing/incorrect migration fail loudly instead of
 * silently losing a user's not-yet-synced data. See the `ListenUpDatabase` KDoc.
 *
 * This is a source-scanning guard rather than a runtime assertion because the flag lives inside the
 * Koin-built `RoomDatabase` and isn't introspectable after construction. Tests run with the working
 * directory at the `sharedLogic` module root — the same assumption the schema-migration tests rely
 * on (`createMigrationTestHelper` resolves `Paths.get("schemas")`) — so these source paths resolve.
 */
class DatabaseMigrationPolicyTest :
    FunSpec({
        val builderSources =
            listOf(
                "src/androidMain/kotlin/com/calypsan/listenup/client/data/local/db/DatabaseModule.kt",
                "src/appleMain/kotlin/com/calypsan/listenup/client/data/local/db/DatabaseModule.kt",
                "src/jvmMain/kotlin/com/calypsan/listenup/client/data/local/db/DatabaseModule.jvm.kt",
            )

        // Tolerate whitespace so a re-add in any formatting is caught.
        val destructiveFallback = Regex("""\.\s*fallbackToDestructiveMigration\s*\(""")

        builderSources.forEach { relativePath ->
            test("$relativePath does not enable destructive migration (MIG-1)") {
                val path = Paths.get(relativePath).toAbsolutePath()
                check(Files.exists(path)) {
                    "DatabaseModule source not found at $path — the migration-policy guard cannot run. " +
                        "Tests must run with the working directory at the sharedLogic module root."
                }
                val source = Files.readString(path)
                if (destructiveFallback.containsMatchIn(source)) {
                    error(
                        "$relativePath calls fallbackToDestructiveMigration — this SILENTLY wipes the " +
                            "local DB, including the unsynced outbox, on a schema mismatch (MIG-1). Remove " +
                            "it and ship a data-preserving Room Migration for the schema change instead.",
                    )
                }
            }
        }
    })
