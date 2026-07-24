package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Guards against the Swift-Export **enum-in-collection** trap on the presentation surface.
 *
 * A Kotlin enum bridges to Swift as an enum, and passing one *into* a Kotlin function as an argument
 * is safe. But the ELEMENTS of a bridged `List<Enum>`/`Set<Enum>` and the KEYS/VALUES of a bridged
 * `Map<Enum, …>` come across as opaque `_KotlinExistential`s. The instant iOS materializes one —
 * iterating the list, subscripting the map, `.first`, `symmetricDifference`, mapping an element —
 * the cast to the Swift enum traps and crashes at runtime:
 *
 * ```
 * Could not cast value of type 'KotlinRuntimeSupport._KotlinExistential<…>' to '…ContributorRole'
 * ```
 *
 * This has bitten the book-edit `ContributorRole` surface **twice** — first as map keys (#1198), then
 * as `List<ContributorRole>` elements — and there is a latent third instance in search
 * (`selectedTypes: Set<SearchHitType>`, materialized by the iOS `SearchObserver`).
 *
 * A `presentation` UI-state type is read by BOTH Compose (which subscripts/iterates enum collections
 * natively — no problem) AND by iOS via Swift Export (which traps). So any PUBLIC enum-typed
 * collection/map on a presentation type is a latent iOS crash. **The safe shape is to expose an
 * `apiValue`-`String` projection for iOS** (see `BookEditUiState.orderedVisibleRoleApiValues`) and
 * keep the enum handling in Kotlin; iOS reconstructs a Swift enum locally to pass back as an argument.
 *
 * Existing occurrences are allow-listed ([ALLOWED_ENUM_COLLECTION_MEMBERS]): the book-edit members
 * already have String projections that iOS reads. Per [NoThrowsInDataLayerRule]'s philosophy, the
 * allowlist keeps them visible while the rule fails the build on any NEW enum collection added to the
 * bridged surface. Removing a name is the signal its iOS-safe projection has shipped.
 */
class NoBridgedEnumCollectionsInUiStateRule :
    FunSpec({
        test("presentation types expose no new public enum-typed collections to Swift Export") {
            val enumNames =
                productionScope()
                    .classes()
                    .filter { it.hasEnumModifier }
                    .mapNotNull { it.name }
                    .toSet()

            val offenders =
                productionScope()
                    .properties()
                    .filter { "/presentation/" in it.path }
                    .filter { it.hasPublicOrDefaultModifier }
                    .filter { it.name !in ALLOWED_ENUM_COLLECTION_MEMBERS }
                    .filter { prop -> exposesEnumCollection(prop.type?.text, enumNames) }
                    .map { "${it.name}: ${it.type?.text} in ${it.path}" }

            offenders.shouldBeEmpty()
        }
    }) {
    companion object {
        private val COLLECTION_HEAD =
            Regex("""^(Map|MutableMap|List|MutableList|Set|MutableSet|Collection|Iterable)\s*<""")

        /**
         * True when [typeText] is a collection/map whose written type arguments include a known enum
         * (matched on whole-word name, so `List<BookWithContributorRole>` — a data class — does not
         * false-match `ContributorRole`).
         */
        fun exposesEnumCollection(
            typeText: String?,
            enumNames: Set<String>,
        ): Boolean {
            val text = typeText ?: return false
            if (!COLLECTION_HEAD.containsMatchIn(text.trim())) return false
            return enumNames.any { enumName -> Regex("""\b${Regex.escape(enumName)}\b""").containsMatchIn(text) }
        }

        /**
         * Enum-typed collections/maps already on the bridged presentation surface, with their audit
         * status. This rule went in AFTER these existed, so the allowlist records the debt (per
         * [NoThrowsInDataLayerRule]'s philosophy) rather than forcing one giant PR; the rule still
         * fails on any NEW enum collection. Removing a name is the signal its iOS-safe projection
         * shipped.
         *
         * **HANDLED** — iOS reads a String projection / argument-taking accessor, never the enum
         * collection: the book-edit `ContributorRole` surface (`orderedVisibleRoleApiValues` +
         * `…ForRole(role)` accessors).
         *
         * **LATENT — iOS materializes the enum, must migrate to an accessor/projection:**
         * - `fallbackSources` (`Map<BookField, String>`): iOS `MetadataMatchMapping` subscripts it by
         *   the `BookField` key (`fallback[field]`) — the #1198 map-key trap, live. Highest priority.
         * - `selectedTypes` (`Set<SearchHitType>`): iOS `SearchObserver` iterates / `symmetricDifference`s it.
         *
         * **LATENT — not currently read by iOS (Android-only sort), but a landmine for any future
         * iOS use:** `booksCategories` / `seriesCategories` / `contributorCategories` (`List<SortCategory>`).
         */
        val ALLOWED_ENUM_COLLECTION_MEMBERS =
            setOf(
                // HANDLED: book-edit ContributorRole surface (iOS reads String projections instead)
                "roleSearchQueries",
                "roleSearchResults",
                "roleSearchLoading",
                "roleOfflineResults",
                "visibleRoles",
                "availableRolesToAdd",
                "orderedVisibleRoles",
                // LATENT (iOS materializes): migrate to an accessor/projection
                "fallbackSources",
                "selectedTypes",
                // LATENT (Android-only today): guard against future iOS materialization
                "booksCategories",
                "seriesCategories",
                "contributorCategories",
            )
    }
}
