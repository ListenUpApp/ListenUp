package com.calypsan.listenup.client.data.sync.domains

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The proof that Guard 1 fires — [AccessGateParityGuard] exercised on synthetic input, the way
 * `SyncWritesGoThroughRepositoryRuleFixtureTest` pins its rule's detection logic. If a domain is
 * per-row access-gated server-side but its client descriptor omits `accessGate`, the offender set
 * is non-empty and the parity spec goes red.
 */
class AccessGateParityGuardFixtureTest :
    FunSpec({

        // A synthetic accessFilterFor with a NEW per-row gate (`activities`) added exactly the way
        // the real bug arrived — a `policy()` branch, no client gate to match it.
        val syntheticSource =
            """
            private const val BOOKS_DOMAIN = "books"
            private const val ACTIVITIES_DOMAIN = "activities"
            private const val LIBRARY_FOLDERS_DOMAIN = "library_folders"
            private const val ADMIN_USER_ROSTER_DOMAIN = "admin_user_roster"

            private fun accessFilterFor(domainName: String, userId: String, role: UserRole): SqlFragment? =
                when (domainName) {
                    BOOKS_DOMAIN -> policy().accessibleBookIdsSql(userId, role)
                    ACTIVITIES_DOMAIN -> policy().accessibleActivityIdsSql(userId, role)
                    LIBRARY_FOLDERS_DOMAIN -> if (isAdmin(role)) null else LIBRARY_FOLDERS_HIDDEN
                    ADMIN_USER_ROSTER_DOMAIN -> if (isAdmin(role)) null else ADMIN_USER_ROSTER_HIDDEN
                    else -> null
                }
            """.trimIndent()

        test("the parser resolves per-row gated wire names through their constants") {
            AccessGateParityGuard.parsePerRowGatedDomains(syntheticSource) shouldBe
                setOf("books", "activities")
        }

        test("the parser classifies the role-gated branches as exempt, not per-row") {
            val perRow = AccessGateParityGuard.parsePerRowGatedDomains(syntheticSource)
            val roleGated = AccessGateParityGuard.parseRoleGatedDomains(syntheticSource)

            perRow shouldNotContain "library_folders"
            perRow shouldNotContain "admin_user_roster"
            roleGated shouldContainExactly setOf("library_folders", "admin_user_roster")
        }

        test("a server per-row gate with no matching client AccessGate is reported as an offender") {
            val serverPerRowGated = AccessGateParityGuard.parsePerRowGatedDomains(syntheticSource)
            // The client has a gate for books but forgot one for the new activities domain — the bug.
            val clientAccessGated = setOf("books")

            val offenders = AccessGateParityGuard.offenders(serverPerRowGated, clientAccessGated)

            offenders shouldContain "activities"
        }

        test("full parity is clean — no offenders when every per-row gate has a client AccessGate") {
            val serverPerRowGated = AccessGateParityGuard.parsePerRowGatedDomains(syntheticSource)
            val clientAccessGated = setOf("books", "activities")

            AccessGateParityGuard.offenders(serverPerRowGated, clientAccessGated) shouldBe emptySet()
        }

        // The silent-bypass shape the guard now closes: a per-row branch written NOT as a bare
        // constant. The old `^\s*(\w+)\s*->` regex matched neither a string-literal condition nor a
        // qualified-name condition, so such an arm vanished from BOTH the per-row set and the exempt
        // set — a per-row-filtered domain would have shipped with no client AccessGate, all sets
        // still green. These arms must now be reported, never silently accepted.
        val nonBareSource =
            """
            private const val BOOKS_DOMAIN = "books"

            private fun accessFilterFor(domainName: String, userId: String, role: UserRole): SqlFragment? =
                when (domainName) {
                    BOOKS_DOMAIN -> policy().accessibleBookIdsSql(userId, role)
                    SyncDomains.ACTIVITIES.name -> policy().accessibleActivityIdsSql(userId, role)
                    "collections" -> policy().accessibleCollectionIdsSql(userId, role)
                    else -> null
                }
            """.trimIndent()

        test("a per-row arm the parser can't resolve fails loud as unparsed, never vanishes") {
            val classification = AccessGateParityGuard.classifyAccessFilter(nonBareSource)

            // Both non-bare per-row arms land in the loud-fail bucket — not silently dropped.
            classification.unparsedArms shouldHaveSize 2
            classification.unparsedArms.any { it.contains("SyncDomains.ACTIVITIES.name") } shouldBe true
            classification.unparsedArms.any { it.contains("\"collections\"") } shouldBe true

            // The invariant the parity spec enforces: not every arm was classified, so the build breaks.
            classification.classifiedArms shouldNotBe classification.totalArms

            // Proof of the old bug: neither non-bare domain leaked silently into the per-row set (it
            // would have been an unmatched offender) nor the exempt set — the guard now catches it.
            classification.perRowGated shouldBe setOf("books")
            classification.roleGated shouldBe emptySet()
        }
    })
