package com.calypsan.listenup.server.konsist

import io.kotest.core.spec.style.FunSpec
import com.lemonappdev.konsist.api.container.KoScope
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll

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
 * Coverage — derived, not listed, so a new write or a new wrapper is seen the day it is written:
 *  - the membership writes are `CollectionBookRepository`'s own public, non-override functions whose name
 *    does not read (`find|get|list|select|count|exists|observe|is|has…`), plus the inherited `upsert`;
 *  - `upsert` / `softDelete` are matched only on the known receiver field names (`collectionBookRepo`
 *    **and** `collectionBookRepository`) so we don't over-match `grantRepo.upsert` /
 *    `collectionRepo.softDelete`; every other write name is unique to the repository and is matched
 *    receiver-agnostically;
 *  - one level of indirection: every non-public top-level wrapper in the collection service's files
 *    ([SERVICE_FILES]) that calls a write (`upsertOrLog`, `releaseHeldBook`) is itself a write token, so a
 *    function that mutates only through a wrapper is still held to the reconcile. A test pins both
 *    wrappers so the derivation cannot silently shrink.
 *
 * G1's `canAccess` assertions remain the receiver-name-agnostic semantic guard behind this tripwire.
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
        test("the membership write set is derived from the repository, and follows its wrappers") {
            val derived = membershipWriteTokens(serverProductionScope())
            withClue("derived membership writes: ${derived.tokens}") {
                derived.writes shouldContainAll
                    setOf(
                        "upsert",
                        "softDelete",
                        "softDeleteAllForCollection",
                        "softDeleteAllForBook",
                        "reviveAllForBooks",
                        "releaseFromInbox",
                    )
                derived.wrappers shouldContainAll setOf("upsertOrLog", "releaseHeldBook")
            }
        }

        test("every collection_books membership mutation also calls reconcileSystemMembership (#680 exclusivity)") {
            val scope = serverProductionScope()
            val membershipTokens = membershipWriteTokens(scope)
            // Gather class-member functions (the precedent shape — `KoScope.functions()` alone does
            // not descend into classes) plus any top-level ones, so no membership mutation escapes.
            val allFunctions = scope.classes().flatMap { it.functions() } + scope.functions()
            val mutators =
                allFunctions
                    .filterNot { it.name in membershipTokens.wrappers }
                    .filter { fn ->
                        val body = stripComments(fn.text)
                        membershipTokens.tokens.any { it in body }
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
                    .map { mutator ->
                        "${mutator.name} @ ${mutator.path} — mutates collection_books without reconcileSystemMembership; " +
                            "ALL_BOOKS exclusivity (#680) must be maintained by every membership mutation, " +
                            "or allowlist with a reason (membership writes tracked: ${membershipTokens.tokens})"
                    }
            offenders.shouldBeEmpty()
        }
    }) {
    companion object {
        /** The repository whose public non-read functions are the membership writes. */
        private const val REPOSITORY = "CollectionBookRepository"

        /**
         * Inherited from the syncable base class rather than declared on [REPOSITORY], so not seen by
         * reading its declarations — the generic upsert every membership insert/revive goes through.
         */
        private val INHERITED_WRITES = setOf("upsert")

        /**
         * Names other repositories share (`grantRepo.upsert`, `collectionRepo.softDelete`), so they are
         * matched only on the membership repository's own field names, never receiver-agnostically.
         */
        private val SHARED_NAMES = setOf("upsert", "softDelete")

        /** The two field spellings a membership repository is held under (`BookRepository` uses the long one). */
        private val RECEIVERS = listOf("collectionBookRepo", "collectionBookRepository")

        /**
         * The files whose non-public top-level functions are wrapper candidates: the service, and the
         * reconciler the service's membership helpers were extracted into.
         */
        private val SERVICE_FILES = setOf("CollectionServiceImpl", "CollectionSystemReconciler")

        /** A function named like this reads; everything else public on [REPOSITORY] writes membership. */
        private val READ_NAME = Regex("""^(find|get|list|select|count|exists|observe|is|has)[A-Z]?.*""")

        /** The membership writes, the private wrappers over them, and the call tokens both produce. */
        private data class MembershipTokens(
            val writes: Set<String>,
            val wrappers: Set<String>,
            val tokens: List<String>,
        )

        /**
         * Reads [REPOSITORY]'s public, non-override, non-read functions as the membership writes, then
         * adds every non-public top-level wrapper in [SERVICE_FILES] whose body calls one of them
         * (one level of indirection: `upsertOrLog`, `releaseHeldBook`).
         */
        private fun membershipWriteTokens(scope: KoScope): MembershipTokens {
            val repository = scope.classes().single { it.name == REPOSITORY }
            val writes =
                repository
                    .functions(includeNested = false)
                    .filterNot { it.hasPrivateModifier || it.hasProtectedModifier || it.hasOverrideModifier }
                    .map { it.name }
                    .filterNot { READ_NAME.matches(it) }
                    .toSet() + INHERITED_WRITES
            val directTokens =
                writes.flatMap { name ->
                    val scoped = RECEIVERS.map { "$it.$name(" }
                    if (name in SHARED_NAMES) scoped else scoped + ".$name("
                }
            val wrappers =
                scope
                    .files
                    .asSequence()
                    .filter { it.name in SERVICE_FILES }
                    .flatMap { it.functions(includeNested = false) }
                    .filter { it.hasPrivateModifier || it.hasInternalModifier }
                    .filter { fn ->
                        val body = stripComments(fn.text)
                        directTokens.any { it in body } ||
                            (
                                fn.receiverType?.name == REPOSITORY &&
                                    writes.any { Regex("""(?<![\w.])$it\(""").containsMatchIn(body) }
                            )
                    }.map { it.name }
                    .toSet()
            return MembershipTokens(writes, wrappers, directTokens + wrappers.map { ".$it(" })
        }

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
