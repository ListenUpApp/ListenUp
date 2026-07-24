package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll

/**
 * Pin all sync wire types (`SyncEvent`, `SyncCursor`, `SyncControl`,
 * `Page`, `DomainDigest`, `DomainList`, `Tag`) to commonMain. Backstops
 * `DtosLiveInCommonMainRule` for the sync surface specifically — that rule
 * guards `@Serializable` classes, but the sync surface also includes sealed
 * interfaces and value classes that carry no annotation in their declaration
 * header.
 */
class SyncEventTypesInCommonMainRule :
    FunSpec({

        test("sync wire types live in :shared/commonMain") {
            val expectedFqns =
                setOf(
                    "com.calypsan.listenup.api.sync.SyncEvent",
                    "com.calypsan.listenup.api.sync.SyncCursor",
                    "com.calypsan.listenup.api.sync.SyncControl",
                    "com.calypsan.listenup.api.sync.Page",
                    "com.calypsan.listenup.api.sync.DomainDigest",
                    "com.calypsan.listenup.api.sync.DomainList",
                    "com.calypsan.listenup.api.sync.Tag",
                )

            val scope = productionScope()

            // Sealed interfaces and data classes may be declared as either
            // `class` or `interface` in the AST — collect both to be safe.
            val foundInCommonMain =
                (scope.classes() + scope.interfaces())
                    .filter { it.path.contains("/commonMain/") }
                    .mapNotNull { it.fullyQualifiedName }
                    .toSet()

            foundInCommonMain shouldContainAll expectedFqns
        }
    })
