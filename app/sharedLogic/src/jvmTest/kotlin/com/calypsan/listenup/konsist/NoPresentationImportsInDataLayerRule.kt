package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard pinning the layering rule that the client data layer never imports
 * presentation-layer symbols. Dependencies point one way — presentation consumes the
 * data layer through domain seams; a `data/` file importing `presentation/`
 * inverts the layering and couples repositories to ViewModel modules. Shared pure
 * helpers belong in `client/core/` (see `DisplayNameParsing.kt`).
 */
class NoPresentationImportsInDataLayerRule :
    FunSpec({
        test("no commonMain client data-layer file imports client presentation symbols") {
            val clientDataFiles =
                productionScope()
                    .files
                    .filter { it.path.contains("/commonMain/") }
                    .filter { it.path.contains("/client/data/") }

            assertScopeNotEmpty(
                clientDataFiles,
                expectedMin = 110,
                why = "commonMain client data-layer files — the side of the layering boundary this rule polices",
            )

            val offenders =
                clientDataFiles
                    .flatMap { file ->
                        file.imports
                            .filter { it.name.startsWith("com.calypsan.listenup.client.presentation.") }
                            .map { "${file.path} -> ${it.name}" }
                    }

            offenders.shouldBeEmpty()
        }
    })
