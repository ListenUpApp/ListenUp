package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Forbid direct writes against `SyncableTable`-extending table objects outside
 * `:server/.../sync/`. Reading is fine; writing must go through the repository
 * so the global revision counter is bumped, the `clientOpId` is captured, and
 * the [ChangeBus][com.calypsan.listenup.server.sync.ChangeBus] sees the event.
 *
 * Heuristic: strip line and block comments (so doc-string code samples don't
 * false-trigger), then scan `:server/.../` files (excluding `sync/` — the
 * substrate itself — and `services/` — where Books-A's per-domain repositories
 * live) for any of the Exposed write operators invoked on a token that matches
 * a `*Table` object known to extend `SyncableTable`.
 *
 * Detected write operators are listed in [WRITE_OPERATORS]. Read-only operators
 * (`.selectAll(`, `.select(`, `.exists(`) are deliberately excluded.
 *
 * This is the load-bearing Konsist rule for the sync substrate — it structurally
 * guarantees that the bus sees every write rather than relying on discipline alone.
 */
class SyncWritesGoThroughRepositoryRule :
    FunSpec({

        test("No Exposed writes against SyncableTable subtypes outside :server/sync/") {
            val scope = productionScope()

            // Discover names of objects that extend SyncableTable.
            val syncableTableNames =
                scope
                    .objects()
                    .filter { obj -> obj.parents().any { it.name == "SyncableTable" } }
                    .map { it.name }
                    .toSet()

            // Nothing to check if no syncable tables exist yet (prevents false-green on
            // empty codebase, but returns early rather than lying with an empty offender list).
            if (syncableTableNames.isEmpty()) return@test

            val offenders =
                scope.files
                    .filter { it.path.contains("/server/") }
                    .filterNot { it.path.contains("/server/sync/") }
                    .filterNot { it.path.contains("/server/services/") }
                    .flatMap { file ->
                        val stripped = stripComments(file.text)
                        WRITE_OPERATORS.flatMap { op ->
                            syncableTableNames.mapNotNull { tableName ->
                                if (stripped.contains("$tableName$op")) {
                                    "$tableName$op in ${file.path}"
                                } else {
                                    null
                                }
                            }
                        }
                    }

            offenders.shouldBeEmpty()
        }
    }) {
    companion object {
        /**
         * Every Exposed write operator that touches a `SyncableTable` outside the
         * repository layer must appear here. Missing operators are silent-corruption
         * risk — the rule will not catch a bypass that uses an operator absent from
         * this set.
         */
        val WRITE_OPERATORS =
            setOf(
                ".upsert(",
                ".update(",
                ".insert(",
                ".deleteWhere(",
                ".batchInsert(",
                ".replace(",
                ".insertIgnore(",
                ".deleteAll(",
                ".deleteIgnoreWhere(",
                ".batchReplace(",
                ".upsertReturning(",
                ".insertAndGetId(",
                ".replaceFromQuery(",
                ".updateReturning(",
            )
    }
}

private fun stripComments(source: String): String =
    source
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
