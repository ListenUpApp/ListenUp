package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The `data.local.db` package — Room database, DAOs, and `@Entity` types — is `:sharedLogic`
 * plumbing reached only through the `domain/repository` seams. Keeping it non-public removes
 * the whole Room layer from every client export path (iOS framework, Swift Export, JS, R8).
 * A new public declaration here would silently re-bloat the export, so make it a build failure.
 */
class DataLocalDbIsInternalRule :
    FunSpec({
        // Symbols that must stay public despite living in data.local.db (cross-module
        // consumers). Empty: the flip is clean.
        // `DownloadEntity` + `DownloadState` were reclaimed by internalizing the
        // `DownloadEnqueuer` seam — its sole cross-module binding (`:sharedUI` desktop's
        // no-arg `JvmDownloadEnqueuer`) moved into `:sharedLogic`'s `desktopDownloadModule`.
        val allowList = emptySet<String>()

        test("no top-level declaration in data/local/db is public") {
            val scope = productionScope()
            val classes =
                scope
                    .classes()
                    .filter {
                        "/data/local/db/" in it.path && it.isTopLevel && it.hasPublicOrDefaultModifier
                    }.map { it.name }
            val interfaces =
                scope
                    .interfaces()
                    .filter {
                        "/data/local/db/" in it.path && it.isTopLevel &&
                            it.hasPublicOrDefaultModifier
                    }.map { it.name }
            val objects =
                scope
                    .objects()
                    .filter {
                        "/data/local/db/" in it.path && it.isTopLevel && it.hasPublicOrDefaultModifier
                    }.map { it.name }
            val properties =
                scope
                    .properties()
                    .filter {
                        "/data/local/db/" in it.path && it.isTopLevel &&
                            it.hasPublicOrDefaultModifier
                    }.map { it.name }
            val functions =
                scope
                    .functions()
                    .filter {
                        "/data/local/db/" in it.path && it.isTopLevel &&
                            it.hasPublicOrDefaultModifier
                    }.map { it.name }
            // Typealiases are top-level-only by Kotlin language rule, so no isTopLevel filter is
            // needed — path + visibility is the complete predicate (Konsist 0.17.3's
            // KoTypeAliasDeclaration lacks isTopLevel, which is why the shared predicate above
            // can't be reused verbatim).
            val typealiases =
                scope
                    .typeAliases
                    .filter { "/data/local/db/" in it.path && it.hasPublicOrDefaultModifier }
                    .map { it.name }
            val offenders =
                (classes + interfaces + objects + properties + functions + typealiases)
                    .filterNot { it in allowList }
            offenders.shouldBeEmpty()
        }
    })
