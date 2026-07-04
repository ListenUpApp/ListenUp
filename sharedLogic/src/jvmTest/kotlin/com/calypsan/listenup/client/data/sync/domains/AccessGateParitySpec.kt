package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.sync.testing.StubAvatarDownloadRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.konsist.productionScope
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Guard 1 (Plan §6, the highest-value structural guard): **every domain the server
 * access-filters *per row* must have a client [AccessGate].**
 *
 * The server side is *parsed* from `SyncRoutes.kt`'s `accessFilterFor` — the single `when`
 * that decides a domain's per-row visibility — so a new server branch is picked up with no
 * edit here. The client side is *runtime-accurate*: the real production [syncDomainCatalog]
 * is built and each [MirroredDomain]'s [MirroredDomain.accessGate] is read directly, so the
 * client half is impossible to get wrong by drift.
 *
 * If a domain is per-row access-filtered server-side but its client descriptor omits an
 * `accessGate`, a revoke/deletion leaves the pruned rows permanently in Room — digest drift
 * plus a stale-visible privacy row. That divergence fails this spec before it can ship.
 *
 * The parse/compare logic lives in [AccessGateParityGuard] and is exercised on synthetic
 * violations by `AccessGateParityGuardFixtureTest` — the guard's proof that it fires.
 */
class AccessGateParitySpec :
    FunSpec({

        fun syncRoutesSource(): String =
            productionScope()
                .files
                .firstOrNull { it.path.endsWith("/SyncRoutes.kt") && it.path.contains("/server/") }
                .shouldNotBeNull()
                .text

        test("every server per-row access-gated domain has a client AccessGate") {
            val source = syncRoutesSource()
            val classification = AccessGateParityGuard.classifyAccessFilter(source)
            val serverPerRowGated = classification.perRowGated

            // Loud-fail on drift: EVERY arm of `when (domainName)` must be classified — per-row,
            // role-gated, or the `else` default. An arm written in a shape the parser doesn't
            // understand (`"activities" ->`, `SyncDomains.ACTIVITIES.name ->`, `A, B ->`,
            // `in setOf(...) ->`) would otherwise vanish from BOTH the per-row set and the exempt
            // set, slipping a per-row-filtered domain past this guard with no client AccessGate.
            withClue(
                "accessFilterFor has an arm the parity guard can't classify: " +
                    "${classification.unparsedArms}. Extend AccessGateParityGuard's parser (or the " +
                    "guard) so the new branch shape is placed — an unclassified arm must break the " +
                    "build, never silently bypass the AccessGate obligation.",
            ) {
                classification.classifiedArms shouldBe classification.totalArms
            }

            // Sanity: the parser actually found the known per-row gates. A parser that silently
            // returns nothing would make the guard vacuously green — refuse that.
            withClue("parser found no per-row gated domains — SyncRoutes.kt shape changed?") {
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
                    "server access-filters these domains per row but their client MirroredDomain has no " +
                        "accessGate — a revoke would strand a stale-visible privacy row (see docs/" +
                        "sync-core-centralization-plan.md §6 Guard 1)",
                ) {
                    AccessGateParityGuard.offenders(serverPerRowGated, clientAccessGated).shouldBeEmpty()
                }
            } finally {
                db.close()
            }
        }

        test("the whole-domain role gates are intentionally listed as AccessGate-exempt") {
            // library_folders / admin_user_roster hide every row from non-admins, so a member holds
            // no rows and needs no client gate. Adding a new role-gate must be a conscious edit to
            // ROLE_GATED_EXEMPT — not a silent path around the per-row obligation.
            val roleGated = AccessGateParityGuard.parseRoleGatedDomains(syncRoutesSource())
            roleGated shouldBe AccessGateParityGuard.ROLE_GATED_EXEMPT
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
