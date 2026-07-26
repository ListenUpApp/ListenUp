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
 * ```
 * Could not cast value of type 'Swift.AnyHashable' to '…SearchHitType'
 * ```
 *
 * (Both messages have been observed in the wild — the first on a `List<ContributorRole>`, the second
 * on a `Set<SearchHitType>`. The boxing differs, the trap does not.)
 *
 * This has bitten the book-edit `ContributorRole` surface **twice** — first as map keys (#1198), then
 * as `List<ContributorRole>` elements — then shipped as two live crashes: the metadata match preview
 * (`fallbackSources`, subscripted per field) and the search screen (`selectedTypes`, materialized on
 * every scope selection). All four are now behind String projections / argument-taking accessors.
 *
 * A `presentation` UI-state type is read by BOTH Compose (which subscripts/iterates enum collections
 * natively — no problem) AND by iOS via Swift Export (which traps). So any PUBLIC enum-typed
 * collection/map on a presentation type is a latent iOS crash. **The safe shape is to expose an
 * `apiValue`-`String` projection for iOS** (see `BookEditUiState.orderedVisibleRoleApiValues`) and
 * keep the enum handling in Kotlin; iOS reconstructs a Swift enum locally to pass back as an argument.
 *
 * Existing occurrences are allow-listed ([ALLOWED_ENUM_COLLECTION_MEMBERS]) — every one of them is
 * there because Compose reads the enum collection natively and should keep doing so. Per
 * [NoThrowsInDataLayerRule]'s philosophy, the allowlist keeps them visible while the rule fails the
 * build on any NEW enum collection added to the bridged surface. The status recorded against each
 * name — whether iOS has a safe projection yet — is the part that matters; see the companion.
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
         * fails on any NEW enum collection. A name stays listed for as long as Compose needs the
         * enum collection — what changes is whether iOS has a safe way to read the same data.
         *
         * **HANDLED** — iOS reads a String projection / argument-taking accessor, never the enum
         * collection itself:
         * - The book-edit `ContributorRole` surface: `orderedVisibleRoleApiValues` +
         *   `…ForRole(role)` accessors.
         * - `fallbackSources` (`Map<BookField, String>`): iOS calls
         *   `PreviewLoadState.Ready.fallbackSourceFor(field)`, so the map subscript happens in
         *   Kotlin. Was the #1198 map-key trap, live in `MetadataMatchMapping` until it crashed the
         *   match preview for any book carrying fallback provenance.
         * - `selectedTypes` (`Set<SearchHitType>`): iOS reads `SearchUiState.selectedTypeNames` and
         *   rebuilds the Swift enum locally. Was live in `SearchObserver` until it crashed the
         *   search screen on every scope selection ("Could not cast value of type
         *   'Swift.AnyHashable' to …SearchHitType" — the `Set` variant of the same trap, where the
         *   elements arrive boxed because `Set` demands `Hashable`).
         *
         * **LATENT — not currently read by iOS (Android-only sort), but a landmine for any future
         * iOS use:** `booksCategories` / `seriesCategories` / `contributorCategories` (`List<SortCategory>`).
         *
         * Note what a green suite does *not* prove: these traps are runtime bridge failures, so a
         * Swift unit test can only catch one if its fixture crosses the boundary the way production
         * does. `MetadataMatchMappingTests` built its `[BookField: String]` in Swift — Swift-boxed
         * keys cast back cleanly — and so stayed green for the entire life of the live crash.
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
                // HANDLED: iOS reads fallbackSourceFor(field) / selectedTypeNames instead
                "fallbackSources",
                "selectedTypes",
                // LATENT (Android-only today): guard against future iOS materialization
                "booksCategories",
                "seriesCategories",
                "contributorCategories",
            )
    }
}
