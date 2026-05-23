package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Every `*ViewModel` in `client/presentation/` that exposes a public
 * `StateFlow<*UiState>` must derive it via `.stateIn(... WhileSubscribed ...)`.
 * Pins the canonical reactive-state pattern from Playback P3.
 *
 * Implementation note: Konsist can't easily AST-match the operator chain
 * across multiple lines, so this rule does a text-level check on the file
 * body — if the file declares a `StateFlow<` referencing a UiState and the
 * file body does not contain the literal substring `WhileSubscribed`, the
 * rule fails. Documented limitation, acceptable trade-off vs the value of
 * pinning the convention.
 */
class ViewModelUsesStateInWhileSubscribedRule :
    FunSpec({
        test("every UiState-exposing ViewModel uses stateIn(WhileSubscribed)") {
            val offenders =
                Konsist
                    .scopeFromProduction()
                    .classes()
                    .filter { it.path.contains("/sharedLogic/") }
                    .filter { it.path.contains("/client/presentation/") }
                    .filter { it.name.endsWith("ViewModel") }
                    .filter { vm -> vm.text.contains("StateFlow<") && vm.text.contains("UiState>") }
                    .filter { vm -> !vm.text.contains("WhileSubscribed") }
                    .map { it.name }
            offenders shouldBe emptyList()
        }
    })
