package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Polices `:server` only — the client's counterpart is [ClientDaoWritesGoThroughDataLayerRule].
 *
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
 * hard-coded — so a new domain is covered the day it lands — and `<write>` is any label in that
 * root's `.sq` file whose statement is not a `SELECT` ([writeLabelsOf]). A verb guess once let
 * `booksQueries.clearManagedCover(…)`, `setCoverColumns`, `touchRevision` and `reviveById` through.
 */
class ServerSyncWritesGoThroughRepositoryRule :
    FunSpec({

        test("no direct SQLDelight writes against a syncable root table outside the repository layer") {
            val writeLabels = syncableRootWriteLabels()

            // Vacuity guard. Replaces the predecessor's silent `return@test` — the exact mechanism
            // that let this rule pass green through the entire Exposed→SQLDelight migration.
            writeLabels.keys.shouldNotBeEmpty()
            withClue("write labels read from the syncable roots' .sq files: $writeLabels") {
                writeLabels.values.sumOf { it.size } shouldBeGreaterThanOrEqual 60
            }

            val offenders =
                productionScope()
                    .files
                    .asSequence()
                    .filter { it.path.contains("/server/") }
                    .filterNot { it.path.contains("/server/sync/") }
                    .filterNot { it.path.contains("/server/services/") }
                    .filterNot { file -> BULK_REWRITE_ALLOWLIST.any { file.path.endsWith(it) } }
                    .flatMap { file -> rootTableWrites(file.text, writeLabels).map { "$it( in ${file.path}" } }
                    .toList()

            offenders.shouldBeEmpty()
        }

        test("every non-synced-column exemption still names a write label of a syncable root") {
            val writeLabels = syncableRootWriteLabels()
            val stale =
                NON_SYNCED_COLUMN_WRITES.keys.filterNot { call ->
                    val (root, label) = call.split(".")
                    label in writeLabels[root].orEmpty()
                }
            stale.shouldBeEmpty()
        }
    }) {
    companion object {
        /** `<root>Queries.<label>(` — a write when the label is a write statement of a syncable root. */
        private val QUERIES_CALL = Regex("""\b([A-Za-z][A-Za-z0-9]*)Queries\s*\.\s*([A-Za-z][A-Za-z0-9]*)\s*\(""")

        /**
         * `root.label` writes that touch a deliberately non-synced column, so they need no revision bump —
         * each cites where the code says so.
         */
        val NON_SYNCED_COLUMN_WRITES: Map<String, String> =
            mapOf(
                "bookRatings.setHardcoverSeen" to
                    "records the Hardcover value the pull last saw; BookRatings.sq: \"Not a sync write: no revision bump\"",
            )

        /** Each syncable root's queries name mapped to its `.sq` file's write labels. */
        fun syncableRootWriteLabels(): Map<String, Set<String>> =
            syncableRepositories()
                .mapNotNull { it.queriesName }
                .distinct()
                .associateWith { writeLabelsOf(it) }

        /** `rootQueries.label` for every syncable-root write in [source], comments stripped, exemptions removed. */
        fun rootTableWrites(
            source: String,
            writeLabels: Map<String, Set<String>>,
        ): List<String> =
            QUERIES_CALL
                .findAll(stripComments(source))
                .filter { it.groupValues[2] in writeLabels[it.groupValues[1]].orEmpty() }
                .map { "${it.groupValues[1]}.${it.groupValues[2]}" }
                .filterNot { it in NON_SYNCED_COLUMN_WRITES }
                .map { "${it.substringBefore('.')}Queries.${it.substringAfter('.')}" }
                .toList()

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
