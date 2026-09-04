package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Pin: the root table of every concrete `SqlSyncableRepository` carries the full sync-substrate
 * column set — [REQUIRED_SYNC_COLUMNS].
 *
 * The catch-up and digest routes read `revision` to order and resume, `deleted_at` to ship
 * tombstones, and `client_op_id` to absorb the client's own echo. A root table missing any of
 * them cannot participate in sync correctly: at best the domain silently never converges, at
 * worst a client loops re-applying its own writes.
 *
 * **This replaces `SyncableTablesExtendSyncableTableRule`.** That rule asserted a paired
 * `<Domain>Table` *object* extended the Exposed base `SyncableTable`, which is how the columns
 * used to be guaranteed. Under SQLDelight there are no table objects — the columns live in the
 * `.sq` schema — so the old rule matched nothing and passed green. The invariant it protected is
 * real, so it is re-expressed here against the schema itself rather than deleted: same intent,
 * checked where the truth now lives.
 */
class SyncableTablesHaveSyncColumnsRule :
    FunSpec({

        test("every syncable repository's root table declares the full sync-substrate column set") {
            val repositories = syncableRepositories()

            // Vacuity guard — the failure mode this whole rule family was rewritten to escape.
            repositories.shouldNotBeEmpty()

            val offenders =
                repositories.mapNotNull { repo ->
                    val columns = repo.rootTable?.let { sqlColumnsOf(it) } ?: return@mapNotNull null
                    val missing = REQUIRED_SYNC_COLUMNS.filterNot { it in columns }
                    if (missing.isEmpty()) {
                        null
                    } else {
                        "${repo.name}: root table `${repo.rootTable}` is missing sync column(s) " +
                            "${missing.joinToString()} — catch-up/digest cannot order, tombstone " +
                            "or echo-absorb this domain correctly"
                    }
                }

            offenders.shouldBeEmpty()
        }
    })
