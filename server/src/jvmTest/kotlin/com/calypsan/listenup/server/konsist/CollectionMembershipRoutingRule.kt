package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The **reconcile-routing tripwire** for the ALL_BOOKS exclusivity invariant (#680/#730).
 *
 * Every production function that mutates a `collection_books` membership must also call
 * `reconcileSystemMembership(` in the same declaration, so a book is dropped out of (or returned to)
 * the everyone-visible ALL_BOOKS substrate on **every** membership change. #680 shipped because a
 * membership mutation forgot exactly this. A text-based tripwire is a cheap *early* signal — the
 * semantic backstop is `AccessInvariantMatrixTest` (G1) — but it fires at the exact line a future
 * membership mutation is written, before any access test even runs.
 *
 * Coverage — the full `CollectionBookRepository` mutation surface, so a bulk re-home can't slip past:
 *  - the two generic mutators (`.upsert(` / `.softDelete(`) are scoped to the known receiver field
 *    names (`collectionBookRepo` **and** `collectionBookRepository` — `BookRepository` uses the long
 *    spelling) so we don't over-match `grantRepo.upsert` / `collectionRepo.softDelete`;
 *  - the three bulk mutators (`softDeleteAllForCollection` / `softDeleteAllForBook` /
 *    `reviveAllForBooks`) are matched receiver-agnostically — those names are unique to
 *    `CollectionBookRepository`, so no scoping is needed and no other repo collides.
 *
 * NOTE ON COUPLING: this is a text tripwire, so a mutation via a *newly-named* helper or a fourth bulk
 * method would need adding here. That is the accepted cost of an early tripwire; G1's `canAccess`
 * assertions are the receiver-name-agnostic semantic guard that catches the escape regardless.
 *
 * Allowlisted (matches a token, legitimately does NOT reconcile):
 *  - `reconcileSystemMembership` — it *is* the reconcile.
 *  - `addToInbox` — quarantine by placement; the book is hidden via the firehose's delivery-time
 *    `canAccess`, not by ALL_BOOKS exclusivity, so a reconcile there would undo the quarantine.
 *  - `writeSystemMembership` — the scanner's atomic new-book insert; a brand-new book's ALL_BOOKS/INBOX
 *    membership is authored directly, not reconciled.
 *  - `softDelete` — `BookRepository.softDelete`'s removal cascade (`softDeleteAllForBook`): the book is
 *    tombstoned *entirely*, not re-homed between collections, so ALL_BOOKS exclusivity is moot.
 *  - `reviveByIds` — `BookRepository.reviveByIds`'s revival (`reviveAllForBooks`): restores the book's
 *    prior membership set verbatim (deletedAt-floored), so there is nothing to reconcile.
 *  - `reviveBookJunctions` — `BookRepository`'s scan-revival helper (`reviveAllForBooks`): same verbatim,
 *    deletedAt-floored restore as `reviveByIds` (only junctions tombstoned by the book's own removal
 *    return), so the membership set is already consistent — nothing to reconcile.
 *  ( `deleteCollection` is NOT allowlisted — it matches `softDeleteAllForCollection` but already loops
 *    `reconcileSystemMembership` per affected book, so the reconcile filter clears it correctly. )
 */
class CollectionMembershipRoutingRule :
    FunSpec({
        test("every collection_books membership mutation also calls reconcileSystemMembership (#680 exclusivity)") {
            // Narrowed to the `server` module's production sources — this rule only inspects server
            // repositories/services. A whole-repo `scopeFromProduction()` parsed every module's PSI
            // (the dominant cost of the server suite) for zero added coverage.
            val scope = Konsist.scopeFromProduction("server")
            // Gather class-member functions (the precedent shape — `KoScope.functions()` alone does
            // not descend into classes) plus any top-level ones, so no membership mutation escapes.
            val allFunctions = scope.classes().flatMap { it.functions() } + scope.functions()
            val mutators =
                allFunctions
                    .filter { fn ->
                        val body = stripComments(fn.text)
                        // Generic mutators — scope to the known receiver fields (both spellings).
                        "collectionBookRepo.upsert(" in body ||
                            "collectionBookRepository.upsert(" in body ||
                            "collectionBookRepo.softDelete(" in body ||
                            "collectionBookRepository.softDelete(" in body ||
                            // Bulk mutators — names unique to CollectionBookRepository, receiver-agnostic.
                            ".softDeleteAllForCollection(" in body ||
                            ".softDeleteAllForBook(" in body ||
                            ".reviveAllForBooks(" in body
                    }
            // Vacuity guard: if the scope narrows to nothing (misconfigured module name, an empty
            // parse), the offender set is trivially empty and the rule passes without ever checking
            // its invariant. A membership-mutating codebase must always surface these call sites.
            require(mutators.isNotEmpty()) {
                "CollectionMembershipRoutingRule found no collection_books mutations in the `server` scope — " +
                    "the scope is misconfigured and the rule would pass vacuously"
            }
            val offenders =
                mutators
                    .filterNot { it.name in ALLOWLIST }
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
        val ALLOWLIST =
            setOf(
                "reconcileSystemMembership",
                "addToInbox",
                "writeSystemMembership",
                "softDelete",
                "reviveByIds",
                "reviveBookJunctions",
            )
    }
}
