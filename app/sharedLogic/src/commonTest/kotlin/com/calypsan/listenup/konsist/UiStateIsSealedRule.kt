package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Every `*UiState` declaration in `client/presentation/` must be a sealed
 * hierarchy (sealed interface or sealed class), not a flat data class with
 * boolean flags. Pins the canonical UI-state pattern.
 *
 * Scope: classes/interfaces ending in `UiState` under
 * `sharedLogic/.../client/presentation/`. The check is structural:
 * `hasSealedModifier` must be true.
 *
 * Legacy exclusions: the 8 types below use the flat `data class` shape and await
 * migration. Do NOT add new types to this list — fix the type instead.
 */
class UiStateIsSealedRule :
    FunSpec({
        // Legacy UiState types deliberately left as flat data classes, awaiting migration.
        // Each entry is the simple class name (without package).
        val legacyExclusions =
            setOf(
                "SettingsUiState",
                "ContributorMetadataUiState",
                "LibrarySetupUiState",
                "BookEditUiState",
                "SyncIndicatorUiState",
                "ContributorEditUiState",
                "SeriesEditUiState",
                "StorageUiState",
            )

        test("every *UiState type in client/presentation/ is sealed (excluding legacy backlog)") {
            val scope = productionScope()
            val offenders =
                (scope.classes() + scope.interfaces())
                    .filter { it.path.contains("/sharedLogic/") }
                    .filter { it.path.contains("/client/presentation/") }
                    .filter { it.name.endsWith("UiState") }
                    .filterNot { it.name in legacyExclusions }
                    .filterNot { it.hasSealedModifier }
                    .map { "${it.name} @ ${it.path}" }

            offenders.shouldBeEmpty()
        }
    })
