package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Bans the legacy shapes of a private mutable field paired with a public read-only view. Explicit
 * backing fields (`val x: StateFlow<T> field = ...`) are Stable as of Kotlin 2.4.0 and are the single
 * canonical shape for mutable-flow state. Four shapes are caught:
 *
 * - `private val _x = MutableStateFlow(...)` / `MutableSharedFlow(...)`, exposed via `_x.asStateFlow()`;
 * - `private val _x = Channel(...)`, exposed via `_x.receiveAsFlow()` — a channel cannot use a backing
 *   field because the public flow is a different object, so the private field is named `xChannel`;
 * - `private var _x`, the same convention spent on a plain variable;
 * - a public `val x: StateFlow<T> = someField.asStateFlow()` property, with or without an underscore.
 */
class NoUnderscoreBackingFieldRule :
    FunSpec({
        val underscoreBacking = Regex("""private val _\w+.*= Mutable(StateFlow|SharedFlow)""")
        val underscoreChannel = Regex("""private val _\w+\s*(:[^=]+)?=\s*Channel\b""")
        val underscoreVar = Regex("""private var _\w+""")
        val stateFlowDual = Regex("""\bval \w+\s*:\s*StateFlow<.*>\s*=\s*\w+\.asStateFlow\(\)""")

        // Legitimate exception: the underscore field is a directly-mutated SOURCE for a *derived*
        // public flow (`_x.stateIn(WhileSubscribed)` / `_x.map { … }`), which explicit backing fields
        // cannot express — the public StateFlow is the derived flow, not the mutable field itself.
        // This is NOT the banned `_x.asStateFlow()` dual-pattern. RestoreFromFileViewModel keeps
        // `_state` to feed `state = _state.stateIn(WhileSubscribed(5_000))` (required by
        // ViewModelUsesStateInWhileSubscribedRule).
        val allowed = setOf("RestoreFromFileViewModel")

        // The explicit-backing-field idiom is canonical across the client KMP modules: the 2a
        // sweep covered :app:sharedLogic; :app:sharedUI, :app:webApp and :contract are folded in here.
        // :server is a separate (Kotlin/Native) track with its own conventions and stays out of scope.
        val inScope = listOf("/sharedLogic/", "/sharedUI/", "/webApp/", "/contract/")

        fun clientClasses(): List<KoClassDeclaration> {
            val classes =
                productionScope()
                    .classes()
                    .filter { cls -> inScope.any { cls.path.contains(it) } }

            assertScopeNotEmpty(
                classes,
                expectedMin = 750,
                why = "classes across the client KMP modules — the surface the 2a sweep made canonical",
            )
            return classes
        }

        fun offendersOf(
            pattern: Regex,
            exempt: Set<String>,
        ): List<String> =
            clientClasses()
                .filter { pattern.containsMatchIn(it.text) }
                .filterNot { it.name in exempt }
                .map { it.name }

        test(
            "no production class uses the underscore Mutable(StateFlow|SharedFlow) backing pattern; use explicit `field =`",
        ) {
            offendersOf(underscoreBacking, allowed) shouldBe emptyList()
        }

        test("no production class names a private Channel with a leading underscore; call it xChannel") {
            offendersOf(underscoreChannel, emptySet()) shouldBe emptyList()
        }

        test("no production class declares a private var with a leading underscore") {
            offendersOf(underscoreVar, emptySet()) shouldBe emptyList()
        }

        test("no production class exposes a StateFlow property as `mutable.asStateFlow()`; use explicit `field =`") {
            offendersOf(stateFlowDual, emptySet()) shouldBe emptyList()
        }
    })
