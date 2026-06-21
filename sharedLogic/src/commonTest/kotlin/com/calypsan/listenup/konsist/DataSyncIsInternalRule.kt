package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The `data.sync` package — sync engine, queue, registry, dispatcher, handlers — is
 * `:sharedLogic` plumbing reached only through the `domain/repository` seams
 * (`SyncRepository`, `LibraryResetHelper`). Keeping the whole package non-public removes
 * it from every client export path (iOS framework, Swift Export, JS, R8). A new public
 * declaration here would silently re-bloat the export, so make it a build failure.
 *
 * Gates top-level classes, interfaces, objects, properties, and functions. Top-level
 * typealiases are not gated: Konsist 0.17.3's `KoTypeAliasDeclaration` lacks `isTopLevel`,
 * so it cannot be filtered with the same predicate as the other declaration kinds.
 */
class DataSyncIsInternalRule :
    FunSpec({
        // Symbols that must stay public despite living in data.sync (cross-module
        // consumers documented in the spec's "Flip exceptions"). Empty if the flip was clean.
        val allowList =
            setOf(
                // Exposed by the cross-module-public `data.connection.ConnectionCoordinator`
                // constructor and `presentation.discover.ActivityFeedViewModel`.
                "SyncEngineState",
                "EngineSnapshot",
                "ConnectionState",
                "ActivityRefreshSignal",
            )

        test("no top-level declaration in data/sync is public") {
            val scope = Konsist.scopeFromProduction()
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
            // Top-level typealiases are not gated here: Konsist 0.17.3's KoTypeAliasDeclaration
            // lacks `isTopLevel`, so it can't share the predicate above.
            val offenders =
                (classes + interfaces + objects + properties + functions).filterNot { it in allowList }
            offenders.shouldBeEmpty()
        }
    })
