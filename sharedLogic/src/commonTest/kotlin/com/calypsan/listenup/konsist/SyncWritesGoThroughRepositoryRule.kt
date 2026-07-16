package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Forbid direct SQLDelight writes against a **syncable root table** outside the repository layer
 * (`:server/sync/` — the substrate — and `:server/services/` — where the per-domain repositories
 * live). Reading is fine; writing must go through the repository so the global revision counter is
 * bumped, the `clientOpId` is captured, and the
 * [ChangeBus][com.calypsan.listenup.server.sync.ChangeBus] sees the event.
 *
 * A bypass is silent: the row changes, no revision is bumped, no event is published, and **every
 * other device simply never learns about the edit** until something unrelated re-upserts that row.
 *
 * **Why this rule was rewritten.** It used to scan for Exposed write operators (`.insert(`,
 * `.upsert(`, …) invoked on `*Table` objects extending `SyncableTable`. The substrate migrated to
 * SQLDelight: the table objects vanished, so its discovery set came back empty and it hit
 * `if (syncableTableNames.isEmpty()) return@test` — a *deliberate silent early-return*, in the rule
 * whose own KDoc called it "the load-bearing Konsist rule for the sync substrate". It has tested
 * nothing since the migration. The `shouldNotBeEmpty` guard below is what that early-return should
 * always have been.
 *
 * Detection is now `<root>Queries.<write>(` where `<root>` is a syncable repository's root queries
 * wrapper, discovered from the repositories themselves via [syncableRepositories] rather than
 * hard-coded — so a new domain is covered the day it lands.
 */
class SyncWritesGoThroughRepositoryRule :
    FunSpec({

        test("no direct SQLDelight writes against a syncable root table outside the repository layer") {
            val syncableQueries = syncableRepositories().mapNotNull { it.queriesName }.toSet()

            // Vacuity guard. Replaces the predecessor's silent `return@test` — the exact mechanism
            // that let this rule pass green through the entire Exposed→SQLDelight migration.
            syncableQueries.shouldNotBeEmpty()

            val offenders =
                productionScope()
                    .files
                    .filter { it.path.contains("/server/") }
                    .filterNot { it.path.contains("/server/sync/") }
                    .filterNot { it.path.contains("/server/services/") }
                    .filterNot { file -> BULK_REWRITE_ALLOWLIST.any { file.path.endsWith(it) } }
                    .flatMap { file ->
                        WRITE_CALL
                            .findAll(stripComments(file.text))
                            .filter { it.groupValues[1] in syncableQueries }
                            .map { "${it.groupValues[1]}Queries.${it.groupValues[2]}( in ${file.path}" }
                            .toList()
                    }

            offenders.shouldBeEmpty()
        }
    }) {
    companion object {
        /**
         * Matches `<root>Queries.<write>(`. The write-verb alternation is the load-bearing part —
         * a verb missing from it is a silent bypass — so [SyncWritesGoThroughRepositoryRuleSelfTest]
         * exercises it against planted samples rather than asserting it equals a copy of itself.
         */
        val WRITE_CALL =
            Regex(
                """\b([A-Za-z][A-Za-z0-9]*)Queries\.([A-Za-z]*(?:insert|update|delete|upsert|replace)[A-Za-z]*)\s*\(""",
                RegexOption.IGNORE_CASE,
            )

        /**
         * Files that write a syncable root table directly **and** re-upsert every touched row
         * through the repository afterwards, so the revision bump and ChangeBus event still
         * happen. Reviewed individually — each entry is a place where the sync invariant is held
         * by hand rather than structurally, so keep this list short and justify additions.
         *
         *  - `GenreServiceImpl` — `executeMove` rewrites an entire subtree's `path`/`depth` in one
         *    bulk statement (a per-row repository upsert would be O(subtree) transactions), then
         *    re-upserts each touched genre so the substrate publishes one `genre.Updated` per row.
         *    See its `executeMove` KDoc.
         */
        val BULK_REWRITE_ALLOWLIST = setOf("/server/api/GenreServiceImpl.kt")
    }
}

private fun stripComments(source: String): String =
    source
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
