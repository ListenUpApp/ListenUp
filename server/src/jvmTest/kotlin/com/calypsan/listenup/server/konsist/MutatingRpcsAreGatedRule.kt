package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

/**
 * Every mutating RPC is gated, or says in writing why it is open to every member.
 *
 * A member's powers are the flags `PermissionPolicy` reads. A new `override` that writes and forgets
 * the gate is not a compile error, not a test failure and not visible on screen — it is a power every
 * member silently holds. This rule makes it a build failure.
 *
 * **Scope.** Every class in `server` production whose name ends in `ServiceImpl`, and every `override`
 * function on it.
 *
 * **Reads are exempt by name** ([READ_PREFIXES]). A write whose name starts like a read slips through;
 * the prefixes are the read verbs the codebase already uses, each checked by hand.
 *
 * **A write passes** if its body, comments stripped, calls one of [GATE_CALLS], or it carries
 * `@OpenToAllMembers(reason = "…")` with a non-blank reason.
 *
 * **Known limit.** The rule trusts the helper names in [GATE_CALLS]; each is a private one-liner over
 * `PermissionPolicy`, `isAdmin()` or an owner comparison. Adding a name here is a review of that helper.
 */
class MutatingRpcsAreGatedRule :
    FunSpec({
        test("every mutating ServiceImpl override is gated or @OpenToAllMembers with a reason") {
            val scope = Konsist.scopeFromProduction("server")
            val services = scope.classes().filter { it.name.endsWith("ServiceImpl") }
            // Vacuity guards: the scope reached server production, and found its services.
            services.size shouldBeGreaterThanOrEqual 25
            services.sumOf { cls -> cls.functions().count { it.hasOverrideModifier } } shouldBeGreaterThanOrEqual 150
            val offenders = findOffenders(scope)
            withClue("Ungated mutating RPCs:\n" + offenders.joinToString("\n")) { offenders.shouldBeEmpty() }
        }
    }) {
    companion object {
        /** Name prefixes that mark an override as a read. */
        val READ_PREFIXES =
            listOf(
                "get", "list", "observe", "search", "find", "browse", "pull",
                "digest", "lookup", "review", "preview", "discover", "current", "last",
            )

        /** Calls that count as a permission gate. */
        val GATE_CALLS =
            listOf(
                "requirePermission(", "permissionPolicy.require(", "requireAdmin(", "requireEditableBook(",
                "requireEditor(", "requireOwner(", "adminGate(", "manageGate(", "writeGate(", ".isAdmin()",
            )

        private val REASONED_ESCAPE = Regex("""@OpenToAllMembers\s*\(\s*(reason\s*=\s*)?"[^"]*\S[^"]*"\s*\)""")

        /** `Class.method @ path` for every override that writes ungated without a reasoned escape. */
        fun findOffenders(scope: KoScope): List<String> =
            scope
                .classes()
                .filter { it.name.endsWith("ServiceImpl") }
                .flatMap { cls ->
                    cls
                        .functions()
                        .filter { it.hasOverrideModifier }
                        .filterNot { fn -> READ_PREFIXES.any { fn.name.startsWith(it) } }
                        .filterNot { fn -> REASONED_ESCAPE.containsMatchIn(stripComments(fn.text)) }
                        .filterNot { fn ->
                            val body = stripComments(fn.text)
                            GATE_CALLS.any { it in body }
                        }.map { fn -> "${cls.name}.${fn.name} @ ${cls.path}" }
                }
    }
}
