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
 * Known false-negative: a top-level `data class XxxUiState(...)` produced
 * before P3 will fail this rule, which is exactly the intent — surfaces still
 * holding the legacy shape get flagged until they're updated.
 */
class UiStateIsSealedRule :
    FunSpec({
        test("every *UiState type in client/presentation/ is sealed") {
            val scope = Konsist.scopeFromProduction()
            val offenders =
                (scope.classes() + scope.interfaces())
                    .filter { it.path.contains("/sharedLogic/") }
                    .filter { it.path.contains("/client/presentation/") }
                    .filter { it.name.endsWith("UiState") }
                    .filterNot { it.hasSealedModifier }
                    .map { "${it.name} @ ${it.path}" }

            offenders.shouldBeEmpty()
        }
    })
