package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Bans the legacy underscore backing-field pattern (`private val _x = MutableStateFlow(...)`
 * exposed via `_x.asStateFlow()`). Explicit backing fields (`val x: StateFlow<T> field = ...`)
 * are Stable as of Kotlin 2.4.0 and are the single canonical shape for mutable-flow state.
 */
class NoUnderscoreBackingFieldRule :
    FunSpec({
        val underscoreBacking = Regex("""private val _\w+.*= Mutable(StateFlow|SharedFlow)""")

        // Legitimate exception: the underscore field is a directly-mutated SOURCE for a *derived*
        // public flow (`_x.stateIn(WhileSubscribed)` / `_x.map { … }`), which explicit backing fields
        // cannot express — the public StateFlow is the derived flow, not the mutable field itself.
        // This is NOT the banned `_x.asStateFlow()` dual-pattern. RestoreFromFileViewModel keeps
        // `_state` to feed `state = _state.stateIn(WhileSubscribed(5_000))` (required by
        // ViewModelUsesStateInWhileSubscribedRule).
        val allowed = setOf("RestoreFromFileViewModel")

        test(
            "no production class uses the underscore Mutable(StateFlow|SharedFlow) backing pattern; use explicit `field =`",
        ) {
            val offenders =
                Konsist
                    .scopeFromProduction()
                    .classes()
                    .filter { underscoreBacking.containsMatchIn(it.text) }
                    .filterNot { it.name in allowed }
                    .map { it.name }
            offenders shouldBe emptyList()
        }
    })
