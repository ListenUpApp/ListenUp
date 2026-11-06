package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard pinning the layering rule that no layer below presentation imports presentation-layer
 * symbols: `client/data/`, `client/playback/`, `client/domain/` and `client/core/`. Dependencies point one
 * way — presentation consumes these layers through domain seams; a file in one of them importing
 * `presentation/` inverts the layering and couples repositories, the player session and the domain model to
 * ViewModel modules. Shared pure helpers belong in the layer that owns the rule they encode (the player's
 * speed and skip arithmetic lives in `client/playback/`) or in `client/core/` (see `DisplayNameParsing.kt`).
 *
 * The rule once covered `client/data/` alone, and the player session — which lives in `client/playback/` —
 * imported its speed and skip helpers from `presentation/player/` without anything noticing.
 */
class NoPresentationImportsBelowPresentationRule :
    FunSpec({
        test("no commonMain file below the presentation layer imports client presentation symbols") {
            val lowerLayerFiles =
                productionScope()
                    .files
                    .filter { it.path.contains("/commonMain/") }
                    .filter { file -> LOWER_LAYER_PATHS.any { file.path.contains(it) } }

            assertScopeNotEmpty(
                lowerLayerFiles,
                expectedMin = 475,
                why = "commonMain client files in data/, playback/, domain/ and core/ — the layers this rule polices",
            )

            val offenders =
                lowerLayerFiles
                    .flatMap { file ->
                        file.imports
                            .filter { it.name.startsWith("com.calypsan.listenup.client.presentation.") }
                            .map { "${file.path} -> ${it.name}" }
                    }

            offenders.shouldBeEmpty()
        }
    }) {
    private companion object {
        val LOWER_LAYER_PATHS = listOf("/client/data/", "/client/playback/", "/client/domain/", "/client/core/")
    }
}
