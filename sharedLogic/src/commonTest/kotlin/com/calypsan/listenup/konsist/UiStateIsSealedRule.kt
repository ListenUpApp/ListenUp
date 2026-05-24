package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Every `*UiState` declaration in `client/presentation/` must be a sealed
 * hierarchy (sealed interface or sealed class), not a flat data class with
 * boolean flags. Pins the canonical UI-state pattern introduced in Playback P3.
 *
 * Scope: classes/interfaces ending in `UiState` under
 * `sharedLogic/.../client/presentation/`. The check is structural:
 * `hasSealedModifier` must be true.
 *
 * Legacy exclusions: the 8 types below were present before P3 and use the flat
 * `data class` shape. They are tracked for future migration in
 * `docs/superpowers/followups.md` ("From Playback P3 — UiState sealed backlog").
 * Do NOT add P3-touched types to this list — fix the type instead.
 */
class UiStateIsSealedRule :
    FunSpec({
        // Legacy non-P3 UiState types deliberately left as flat data classes; tracked for
        // future migration. Each entry is the simple class name (without package).
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
            val scope = Konsist.scopeFromProduction()
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
