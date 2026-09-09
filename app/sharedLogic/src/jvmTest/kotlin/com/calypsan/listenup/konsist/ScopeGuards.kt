package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

/**
 * Assert that a Konsist rule's DISCOVERY SET — the population it narrowed to before applying
 * its violation predicate — is at least [expectedMin] big.
 *
 * A rule whose discovery set comes back empty passes green while testing nothing. That is not
 * hypothetical: [SyncWritesGoThroughRepositoryRule] matched zero declarations for the whole
 * Exposed→SQLDelight migration, silently, in the rule its own KDoc called load-bearing.
 *
 * [expectedMin] is a COLLAPSE floor, not a ratchet — pick a number comfortably below the
 * current honest population so an ordinary deletion never trips it. `expectedMin = 0` is a
 * legitimate answer for a reintroduction ratchet whose population is empty by design; state
 * that in [why] so the zero is a decision on the record rather than an oversight.
 *
 * @param why what the population is and why this minimum is the right one. Surfaces in the
 *   failure message, where it is the only context the reader gets.
 */
internal fun assertScopeNotEmpty(
    declarations: Collection<*>,
    expectedMin: Int,
    why: String,
) {
    withClue(
        "Konsist discovery set collapsed: expected at least $expectedMin declaration(s), " +
            "found ${declarations.size}. $why",
    ) {
        declarations.size shouldBeGreaterThanOrEqual expectedMin
    }
}

/**
 * Strips a generic argument list from a type name: `SyncDomainHandler<T>` → `SyncDomainHandler`.
 *
 * **Always compare a Konsist parent name through this.** `KoParentDeclaration.name` carries the
 * type arguments as written, so `p.name == "SomeInterface"` silently matches nothing the moment
 * that supertype is generic — the filter compiles, the rule runs, and it polices an empty set.
 *
 * That is not a hypothetical. [SyncDomainHandlersSelfRegisterRule] and
 * [SyncDomainHandlersUseAppResultRule] both compared against the bare `"SyncDomainHandler"` while
 * the real parent name was `"SyncDomainHandler<T>"`, so both reported green over a population of
 * zero until 2026-09-09. The two rules that got it right —
 * [OnlyComposedHandlerImplementsSyncDomainHandlerRule] and [syncableRepositories] — each carried
 * their own private copy of this one-liner, which is why the fix never propagated to the two that
 * did not. One canonical helper, so the next generic supertype cannot reopen the hole.
 */
internal fun String.bareTypeName(): String = substringBefore('<')
