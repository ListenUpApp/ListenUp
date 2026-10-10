package com.calypsan.listenup.konsist

import com.calypsan.listenup.konsist.NoBridgedEnumCollectionsInUiStateRule.Companion.exposesEnumCollection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Self-test for [NoBridgedEnumCollectionsInUiStateRule]'s type matcher.
 *
 * **Why it exists.** The rule's own test asserts the production tree is *clean*, so it passes
 * whether the matcher works or not. A matcher that only recognised a collection at the head of the
 * written type let `StateFlow<List<Enum>>` and `ImmutableList<Enum>` through for as long as nobody
 * wrote one. Plant the shapes and demand they are caught; plant safe shapes and demand they are not.
 */
class NoBridgedEnumCollectionsInUiStateRuleSelfTest :
    FunSpec({
        val enums = setOf("ContributorRole", "MatchTier")

        context("an enum inside a collection is flagged") {
            listOf(
                "List<ContributorRole>",
                "Map<MatchTier, Int>",
                "Set<ContributorRole>?",
                "StateFlow<List<ContributorRole>>",
                "ImmutableList<ContributorRole>",
                "PersistentSet<MatchTier>",
                "Flow<Map<ContributorRole, String>>",
            ).forEach { type ->
                test(type) { exposesEnumCollection(type, enums) shouldBe true }
            }
        }

        context("everything else is left alone") {
            listOf(
                "List<BookWithContributorRole>",
                "List<String>",
                "ContributorRole",
                "(ContributorRole) -> Unit",
                "StateFlow<ContributorRole>",
            ).forEach { type ->
                test(type) { exposesEnumCollection(type, enums) shouldBe false }
            }
        }
    })
