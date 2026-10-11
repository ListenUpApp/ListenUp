package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Guards against the Swift-Export **enum-in-collection** trap on the whole bridged surface: public
 * properties and function return types in client presentation, client domain models, and the
 * contract's DTOs and sync types.
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
 * A UI-state type is read by BOTH Compose (which subscripts/iterates enum collections
 * natively — no problem) AND by iOS via Swift Export (which traps). So any PUBLIC enum-typed
 * collection/map on a bridged type — however deeply wrapped, e.g. `StateFlow<List<Enum>>` — is a latent iOS crash. **The safe shape is to expose an
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
        test("the bridged surface exposes no new public enum-typed collections to Swift Export") {
            val enumNames =
                productionScope()
                    .classes()
                    .filter { it.hasEnumModifier }
                    .mapNotNull { it.name }
                    .toSet()

            // Two independent collapse modes, so two guards. `exposesEnumCollection` returns false
            // for every type once `enumNames` is empty, and the property set is the surface itself —
            // either one emptying makes this rule green over an unexamined bridged surface.
            assertScopeNotEmpty(
                enumNames,
                expectedMin = 50,
                why = "known enum type names — the vocabulary exposesEnumCollection matches against",
            )

            val bridgedProperties =
                productionScope()
                    .properties()
                    .filter { isOnBridgedSurface(it.path) }
                    .filter { it.hasPublicOrDefaultModifier }
            val bridgedFunctions =
                productionScope()
                    .functions()
                    .filter { isOnBridgedSurface(it.path) }
                    .filter { it.hasPublicOrDefaultModifier }

            assertScopeNotEmpty(
                bridgedProperties,
                expectedMin = BRIDGED_PROPERTIES_FLOOR,
                why = "public properties on the bridged surface — what this rule polices",
            )
            assertScopeNotEmpty(
                bridgedFunctions,
                expectedMin = BRIDGED_FUNCTIONS_FLOOR,
                why = "public functions on the bridged surface — their return types are policed too",
            )

            val propertyTypes =
                bridgedProperties.map { property ->
                    Triple(property.containingFile.nameWithExtension, property.name, property.type?.text)
                }
            val returnTypes =
                bridgedFunctions.map { function ->
                    Triple(function.containingFile.nameWithExtension, function.name, function.returnType?.text)
                }
            val offenders =
                (propertyTypes + returnTypes)
                    .asSequence()
                    .filter { (file, name, _) -> "$file:$name" !in ALLOWED_ENUM_COLLECTION_MEMBERS }
                    .filter { (_, _, type) -> exposesEnumCollection(type, enumNames) }
                    .map { (file, name, type) -> "$file:$name: ${type ?: "untyped"}" }
                    .toList()

            offenders.shouldBeEmpty()
        }
    }) {
    companion object {
        private const val BRIDGED_PROPERTIES_FLOOR = 2_580 // ~70% of the 3,687 measured
        private const val BRIDGED_FUNCTIONS_FLOOR = 430 // ~70% of the 614 measured

        /**
         * The commonMain code Swift Export hands to iOS: client presentation and domain models, and the
         * contract's DTOs and sync types. Anything else either stays Kotlin-side or is not enum-bearing.
         */
        private fun isOnBridgedSurface(path: String): Boolean =
            (
                "/app/sharedLogic/src/commonMain/" in path &&
                    ("/client/presentation/" in path || "/client/domain/model/" in path)
            ) ||
                (
                    "/contract/src/commonMain/" in path &&
                        ("/api/dto/" in path || "/api/sync/" in path)
                )

        private val COLLECTION_OPEN =
            Regex(
                """\b(?:Map|MutableMap|List|MutableList|Set|MutableSet|Collection|Iterable|""" +
                    """ImmutableList|ImmutableSet|ImmutableMap|PersistentList|PersistentSet|PersistentMap)\s*<""",
            )

        /**
         * True when [typeText] contains a collection/map — at any depth, so `StateFlow<List<Enum>>`
         * counts — whose own written type arguments include a known enum (matched on whole-word name,
         * so `List<BookWithContributorRole>` — a data class — does not false-match `ContributorRole`).
         * An enum that is merely a function-type parameter or a non-collection argument is safe.
         */
        fun exposesEnumCollection(
            typeText: String?,
            enumNames: Set<String>,
        ): Boolean {
            val text = typeText ?: return false
            return COLLECTION_OPEN.findAll(text).any { open ->
                val arguments = balancedArguments(text, open.range.last + 1)
                enumNames.any { enumName -> Regex("""\b${Regex.escape(enumName)}\b""").containsMatchIn(arguments) }
            }
        }

        /** The text from [start] (just past a `<`) up to the `>` that closes it, or to the end if unbalanced. */
        private fun balancedArguments(
            text: String,
            start: Int,
        ): String {
            var depth = 1
            var end = start
            while (end < text.length && depth > 0) {
                when (text[end]) {
                    '<' -> depth++
                    '>' -> if (text.getOrNull(end - 1) != '-') depth--
                }
                end++
            }
            return text.substring(start, end)
        }

        /**
         * Enum-typed collections/maps already on the bridged surface, keyed `<File.kt>:<member name>` so a
         * same-named member elsewhere is still caught, with their audit
         * status. This rule went in AFTER these existed, so the allowlist records the debt (per
         * [NoThrowsInDataLayerRule]'s philosophy) rather than forcing one giant PR; the rule still
         * fails on any NEW enum collection. A name stays listed for as long as Compose needs the
         * enum collection — what changes is whether iOS has a safe way to read the same data.
         *
         * **HANDLED** — iOS reads a String projection / argument-taking accessor, never the enum
         * collection itself:
         * - The book-edit `ContributorRole` surface: `orderedVisibleRoleApiValues` +
         *   `…ForRole(role)` accessors.
         * - `fallbackSources` (`Map<BookField, String>`, since removed): iOS called
         *   `PreviewLoadState.Ready.fallbackSourceFor(field)`, so the map subscript happened in
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
                "BookEditModels.kt:roleSearchQueries",
                "BookEditModels.kt:roleSearchResults",
                "BookEditModels.kt:roleSearchLoading",
                "BookEditModels.kt:roleOfflineResults",
                "BookEditModels.kt:visibleRoles",
                "BookEditModels.kt:availableRolesToAdd",
                "BookEditModels.kt:orderedVisibleRoles",
                // HANDLED: iOS reads selectedTypeNames instead
                // HANDLED: iOS reads ReviewUiState.Ready.alreadySameNames instead
                "BookMatchModels.kt:alreadySame",
                "SearchViewModel.kt:selectedTypes",
                // LATENT (Android-only today): guard against future iOS materialization
                "SortState.kt:booksCategories",
                "SortState.kt:seriesCategories",
                "SortState.kt:contributorCategories",
                // LATENT (not read by iOS today; verified by grep of app/iosApp): contract and domain types
                // that carry an enum collection. The Room/sync layer and Compose read them natively.
                // PermissionPreset.pickable is spelled out in Swift (UserPermissionsReadyModel.pickablePresets).
                "PermissionPresets.kt:pickable",
                "EditableTypes.kt:roles",
                "Permission.kt:known",
                "PeopleFind.kt:roles",
                "ImportDtos.kt:bookMatchCounts",
                "AnalyzedBook.kt:fieldProvenance",
                "SidecarCuration.kt:fieldProvenance",
                "MetadataDtos.kt:fallbackFields",
                "BookSyncPayload.kt:fieldProvenance",
                "ContributorSyncPayload.kt:fieldProvenance",
                "ReviewChoices.kt:toggleLabel",
                "ServerInfo.kt:advertisedPermissions",
            )
    }
}
