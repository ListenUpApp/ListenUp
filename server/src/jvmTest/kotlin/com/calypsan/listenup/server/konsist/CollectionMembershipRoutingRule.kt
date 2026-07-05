package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The **reconcile-routing tripwire** for the ALL_BOOKS exclusivity invariant (#680/#730).
 *
 * Every production function that mutates a `collection_books` membership through the
 * `collectionBookRepo` — `collectionBookRepo.upsert(` / `collectionBookRepo.softDelete(` — must also
 * call `reconcileSystemMembership(` in the same declaration, so a book is dropped out of (or returned
 * to) the everyone-visible ALL_BOOKS substrate on **every** membership change. #680 shipped because a
 * membership mutation forgot exactly this. A text-based tripwire is a cheap *early* signal — the
 * semantic backstop is `AccessInvariantMatrixTest` (G1) — but it fires at the exact line a future
 * membership mutation is written, before any access test even runs.
 *
 * Allowlisted (matches the token, legitimately does NOT reconcile):
 *  - `reconcileSystemMembership` — it *is* the reconcile.
 *  - `addToInbox` — quarantine by placement; the book is hidden via the firehose's delivery-time
 *    `canAccess`, not by ALL_BOOKS exclusivity, so a reconcile there would undo the quarantine.
 *  - `writeSystemMembership` — the scanner's atomic new-book insert; a brand-new book's ALL_BOOKS/INBOX
 *    membership is authored directly, not reconciled.
 */
class CollectionMembershipRoutingRule :
    FunSpec({
        test("every collection_books membership mutation also calls reconcileSystemMembership (#680 exclusivity)") {
            val scope = Konsist.scopeFromProduction()
            // Gather class-member functions (the precedent shape — `KoScope.functions()` alone does
            // not descend into classes) plus any top-level ones, so no membership mutation escapes.
            val allFunctions = scope.classes().flatMap { it.functions() } + scope.functions()
            val offenders =
                allFunctions
                    .filter { fn ->
                        val body = stripComments(fn.text)
                        "collectionBookRepo.upsert(" in body || "collectionBookRepo.softDelete(" in body
                    }.filterNot { it.name in ALLOWLIST }
                    .filterNot { "reconcileSystemMembership(" in stripComments(it.text) }
                    .map {
                        "${it.name} @ ${it.path} — mutates collection_books without reconcileSystemMembership; " +
                            "ALL_BOOKS exclusivity (#680) must be maintained by every membership mutation, " +
                            "or allowlist with a reason"
                    }
            offenders.shouldBeEmpty()
        }
    }) {
    companion object {
        /** Functions that touch the token but legitimately do not reconcile — see the class KDoc. */
        val ALLOWLIST = setOf("reconcileSystemMembership", "addToInbox", "writeSystemMembership")
    }
}
