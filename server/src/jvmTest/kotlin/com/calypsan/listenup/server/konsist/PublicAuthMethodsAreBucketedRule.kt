package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

/**
 * Every method on the `AuthServicePublic` contract is throttled, or is a listed exemption.
 *
 * `/api/rpc/public` is the one mount an unauthenticated stranger can reach, and the per-IP token
 * bucket (`AuthRateBucket` + `AuthServiceImpl.enforceRate` + `LoginRateLimiter`) is the whole of
 * what makes that safe — auth has no REST mirror, so no Ktor route-level plugin ever runs for it.
 * `AuthRateBucket` states the invariant this rule pins, in `RESET_ROOT_PASSWORD`'s own KDoc:
 *
 * > "Consistency is the point: an un-bucketed auth method is an exception a reader has to go and
 * > verify, so it should not exist without a reason."
 *
 * `observePasswordResetStatus` is what that costs when it is only prose: it shipped un-bucketed
 * while both of its `observe*` siblings were bucketed, and each of its open subscriptions holds a
 * poll loop that never completes while the ticket is pending. Nothing failed; a reader simply had
 * to notice.
 *
 * The method list is read from the **contract** interface, not from the implementation, so a
 * method added to the public surface is covered the moment it exists — an implementation-derived
 * list would only ever check what someone remembered to write down.
 */
class PublicAuthMethodsAreBucketedRule :
    FunSpec({

        /**
         * Methods that are public and deliberately un-bucketed. Each needs a stated reason in
         * its own KDoc; this list is the second place a reader can find them.
         *
         * `issueSocketTicket`: costs a signature verification, requires a valid token to yield
         * anything, and a client legitimately mints one per reconnect — a bucket here would
         * throttle recovery from exactly the network conditions that cause reconnects.
         */
        val exempt = setOf("issueSocketTicket")

        test("every AuthServicePublic method is bucketed or exempt") {
            val production = Konsist.scopeFromProduction()

            val contractMethods =
                production
                    .interfaces()
                    .first { it.name == "AuthServicePublic" }
                    .functions()
                    .map { it.name }

            val implementation =
                production
                    .classes()
                    .first { it.name == "AuthServiceImpl" }

            val checkedMethods = contractMethods.filterNot { it in exempt }

            // Guard against a vacuous pass: the public contract carries a dozen methods, and a
            // rule that silently matches nothing has stopped guarding.
            checkedMethods.size shouldBeGreaterThanOrEqual 10

            val offenders =
                checkedMethods.mapNotNull { name ->
                    val override = implementation.functions().firstOrNull { it.name == name }
                    when {
                        override == null -> "$name has no override in AuthServiceImpl"
                        !stripComments(override.text).contains("enforceRate(") ->
                            "$name does not call enforceRate(...) — bucket it, or add it to `exempt` with a reason"

                        else -> null
                    }
                }

            offenders.shouldBeEmpty()
        }
    })
