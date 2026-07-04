package com.calypsan.listenup.client.data.sync.domains

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

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
    })
