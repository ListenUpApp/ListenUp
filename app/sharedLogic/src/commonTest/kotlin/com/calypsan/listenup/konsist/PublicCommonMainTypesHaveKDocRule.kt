package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.ext.list.modifierprovider.withPublicOrDefaultModifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Konsist guard pinning the rule that every public type defined in commonMain has a
 * KDoc block.
 *
 * KDoc on commonMain types is load-bearing — the same type is consumed from Android,
 * desktop, iOS, and (sometimes) the server. A reader scanning the type to understand
 * how to use it doesn't have a parent class or surrounding callsite to lean on; KDoc
 * is what carries the contract across platforms.
 *
 * **Covers classes, interfaces AND objects.** It used to read `scope.classes()` alone, which in
 * Konsist excludes interfaces and objects — so despite the test name promising "class/interface",
 * every public `sealed interface` and `object` in commonMain was invisible to it. Not
 * hypothetical: `ReaderLineKind`, `CreateInviteStatus` and `CreateInviteErrorType` all shipped
 * undocumented while this rule stayed green. `DataLocalDbIsInternalRule` already enumerates all
 * three kinds; this now matches it.
 */
class PublicCommonMainTypesHaveKDocRule :
    FunSpec({
        test("every public commonMain class/interface/object has a KDoc block") {
            val scope = productionScope()

            // Konsist models each declaration kind with its own typed list, so run the identical
            // filter over all three rather than trying to unify them behind a common supertype.
            val classes =
                scope
                    .classes()
                    .filter { it.path.contains("/commonMain/") }
                    // Exclude third-party library sources that Konsist may materialise under a
                    // path containing "/commonMain/" (e.g. Koin's InstanceRegistry extracted
                    // from a sources jar into <worktree-root>/commonMain/org/koin/…).
                    // First-party types always live under the com.calypsan.listenup namespace.
                    .filter { it.packagee?.name?.startsWith("com.calypsan.listenup") == true }
                    .withPublicOrDefaultModifier()
            val interfaces =
                scope
                    .interfaces()
                    .filter { it.path.contains("/commonMain/") }
                    .filter { it.packagee?.name?.startsWith("com.calypsan.listenup") == true }
                    .withPublicOrDefaultModifier()
            val objects =
                scope
                    .objects()
                    // A `companion object` is an implementation detail of its enclosing type —
                    // that type carries the KDoc, and demanding a second block on every companion
                    // is noise, not documentation. (e.g. BookId.Companion in ValueClasses.kt.)
                    .filter { !it.hasCompanionModifier }
                    // Top-level objects only. A nested `data object` is a sealed VARIANT
                    // (`data object Mp3 : AudioFormat`), already described by its parent's KDoc —
                    // which typically enumerates every variant in one place. Demanding a second
                    // block per variant yields 69 restatements of the parent, and a doc that
                    // paraphrases its neighbour is worse than no doc. Variants carrying real data
                    // are `data class`, so `.classes()` above still holds them to the rule.
                    .filter { it.isTopLevel }
                    .filter { it.path.contains("/commonMain/") }
                    .filter { it.packagee?.name?.startsWith("com.calypsan.listenup") == true }
                    .withPublicOrDefaultModifier()

            // Vacuity guards: prove each kind is actually being discovered. Without these, a
            // Konsist API or path-shape change would leave the rule matching nothing and passing
            // green — the failure mode that hollowed out the sync-substrate rules.
            classes.shouldNotBeEmpty()
            interfaces.shouldNotBeEmpty()
            objects.shouldNotBeEmpty()

            val offenders =
                classes.filter { !it.hasKDoc }.map { "${it.fullyQualifiedName} @ ${it.path}" } +
                    interfaces.filter { !it.hasKDoc }.map { "${it.fullyQualifiedName} @ ${it.path}" } +
                    objects.filter { !it.hasKDoc }.map { "${it.fullyQualifiedName} @ ${it.path}" }

            offenders.shouldBeEmpty()
        }
    })
