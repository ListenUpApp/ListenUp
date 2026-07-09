package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The `data.sync` package — sync engine, queue, registry, dispatcher, handlers — is
 * `:sharedLogic` plumbing reached only through the `domain/repository` seams
 * (`SyncRepository`, `LibraryResetHelper`). Keeping the whole package non-public removes
 * it from every client export path (iOS framework, Swift Export, JS, R8). A new public
 * declaration here would silently re-bloat the export, so make it a build failure.
 *
 * Gates top-level classes, interfaces, objects, properties, functions, and typealiases.
 * Typealiases are top-level-only by Kotlin language rule, so a path + visibility predicate
 * gates them completely — the `isTopLevel` filter the other kinds use is unnecessary (and
 * Konsist 0.17.3's `KoTypeAliasDeclaration` lacks it anyway).
 */
class DataSyncIsInternalRule :
    FunSpec({
        // Empty — the entire data.sync package is internal, no exceptions. The former 4
        // (SyncEngineState/EngineSnapshot/ConnectionState/ActivityRefreshSignal) were reclaimed by
        // narrowing the ConnectionCoordinator + ActivityFeedViewModel constructors to internal.
        val allowList = emptySet<String>()

        test("no top-level declaration in data/sync is public") {
            val scope = productionScope()
            // Only top-level declarations are gated: nested members of an allow-listed public type
            // (e.g. ConnectionState's sealed subtypes) are public by construction and not export entry
            // points on their own. `it !in allowList` covers them transitively via their parent.
            val classes =
                scope
                    .classes()
                    .filter { "/data/sync/" in it.path && it.isTopLevel && it.hasPublicOrDefaultModifier }
                    .map { it.name }
            val interfaces =
                scope
                    .interfaces()
                    .filter { "/data/sync/" in it.path && it.isTopLevel && it.hasPublicOrDefaultModifier }
                    .map { it.name }
            val objects =
                scope
                    .objects()
                    .filter { "/data/sync/" in it.path && it.isTopLevel && it.hasPublicOrDefaultModifier }
                    .map { it.name }
            val properties =
                scope
                    .properties()
                    .filter { "/data/sync/" in it.path && it.isTopLevel && it.hasPublicOrDefaultModifier }
                    .map { it.name }
            val functions =
                scope
                    .functions()
                    .filter { "/data/sync/" in it.path && it.isTopLevel && it.hasPublicOrDefaultModifier }
                    .map { it.name }
            // Typealiases are top-level-only by Kotlin language rule, so no isTopLevel filter is
            // needed — path + visibility is the complete predicate (Konsist 0.17.3's
            // KoTypeAliasDeclaration lacks isTopLevel, which is why the shared predicate above
            // can't be reused verbatim).
            val typealiases =
                scope
                    .typeAliases
                    .filter { "/data/sync/" in it.path && it.hasPublicOrDefaultModifier }
                    .map { it.name }
            val offenders =
                (classes + interfaces + objects + properties + functions + typealiases)
                    .filterNot { it in allowList }
            offenders.shouldBeEmpty()
        }
    })
