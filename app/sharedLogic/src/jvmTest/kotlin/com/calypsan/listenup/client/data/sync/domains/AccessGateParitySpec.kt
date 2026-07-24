package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.sync.testing.StubAvatarDownloadRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.server.sync.perRowAccessGatedSyncDomains
import com.calypsan.listenup.server.sync.roleGatedSyncDomains
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Guard 1 (Plan §6, the highest-value structural guard): **every domain the server
 * access-filters *per row* must have a client [AccessGate], and vice versa.**
 *
 * Both halves are read at runtime — no source parsing. The server half is its declared
 * access-filter catalog ([perRowAccessGatedSyncDomains], derived from `SyncRoutes.ACCESS_FILTERS`);
 * the client half is the real production [syncDomainCatalog], with each [MirroredDomain]'s
 * [MirroredDomain.accessGate] read directly. Asserting the two sets are equal catches both failure
 * directions:
 *  - a server per-row gate with no client `AccessGate` → a revoke/deletion leaves the pruned rows
 *    permanently in Room (digest drift + a stale-visible privacy row);
 *  - a client `AccessGate` with no server per-row gate → a gate that never fires.
 *
 * The whole-domain role gates (`library_folders` / `admin_user_roster`) hide every row from
 * non-admins, so a member holds no rows and needs no client gate; they are the conscious-edit
 * [AccessGateParityGuard.ROLE_GATED_EXEMPT] set, asserted separately against the server's
 * [roleGatedSyncDomains].
 */
class AccessGateParitySpec :
    FunSpec({

        test("the server's per-row access-gated domains exactly match the client AccessGate domains") {
            val serverPerRowGated = perRowAccessGatedSyncDomains

            // Sanity: the declared catalog still holds the known per-row gates. Guards against a
            // catalog gutted to empty (which would make the parity check vacuously green).
            withClue("server ACCESS_FILTERS no longer declares the expected per-row gates") {
                serverPerRowGated shouldBe
                    setOf("books", "activities", "collections", "collection_shares", "collection_books")
            }

            val db = createInMemoryTestDatabase()
            try {
                val clientAccessGated =
                    testCatalog(db)
                        .mirrored
                        .filter { it.accessGate != null }
                        .map { it.key.name }
                        .toSet()

                withClue(
                    "server per-row access-filtered domains and client MirroredDomain accessGate domains " +
                        "diverge — a server-gated domain with no client gate strands a stale-visible privacy " +
                        "row on revoke; a client gate with no server gate never fires (see docs/" +
                        "sync-core-centralization-plan.md §6 Guard 1)",
                ) {
                    clientAccessGated shouldBe serverPerRowGated
                }
            } finally {
                db.close()
            }
        }

        test("the whole-domain role gates are intentionally listed as AccessGate-exempt") {
            // library_folders / admin_user_roster hide every row from non-admins, so a member holds
            // no rows and needs no client gate. Adding a new role gate to the server's ACCESS_FILTERS
            // must be a conscious edit to ROLE_GATED_EXEMPT — not a silent path around the per-row
            // obligation.
            roleGatedSyncDomains shouldBe AccessGateParityGuard.ROLE_GATED_EXEMPT
        }
    })

private fun testCatalog(db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase): SyncDomainCatalog =
    syncDomainCatalog(
        database = db,
        mapper = BookEntityMapper(),
        imageStorage = stubImageStorage(),
        authSession = FakeAuthSession(userId = "parity-user"),
        avatarDownloadRepository = StubAvatarDownloadRepository(),
        pingPresence = {},
        refetchServerInfo = {},
        refetchPreferences = {},
    )
