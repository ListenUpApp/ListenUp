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
 * **Reads are exempt by name** ([READ_PREFIXES]) — the whole word, not any prefix: `list` and
 * `listShelves` are reads, `listenTo` is not. A read-named override that writes anyway (a
 * [WRITE_MARKERS] hit: a `…Queries.insert/update/delete/upsert…` call, a `.publish(`, the `changeBus`)
 * loses the exemption and is checked like any write.
 *
 * **A write passes** if its body, comments stripped, calls one of [GATE_CALLS], denies non-admins in
 * the [ADMIN_DENIAL_SHAPE] (a bare `.isAdmin()` that denies nothing does not count), or it carries
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
                "get",
                "list",
                "observe",
                "search",
                "find",
                "browse",
                "pull",
                "digest",
                "lookup",
                "review",
                "preview",
                "discover",
                "current",
                "currently",
                "last",
            )

        /** Calls that count as a permission gate. */
        val GATE_CALLS =
            listOf(
                "requirePermission(",
                "permissionPolicy.require(",
                "requireAdmin(",
                "requireEditableBook(",
                "requireEditor(",
                "requireOwner(",
                "adminGate(",
                "manageGate(",
                "writeGate(",
            )

        /** Calls that mark a body as a write, whatever its name says. */
        val WRITE_MARKERS =
            listOf(
                Regex("""Queries\s*\.\s*(insert|update|delete|upsert)\w*\s*\("""),
                Regex("""\.publish\("""),
                Regex("""\bchangeBus\b"""),
            )

        /** True when [name] is [prefix] itself, or [prefix] followed by a new camel-case word. */
        fun isReadName(name: String): Boolean =
            READ_PREFIXES.any { prefix ->
                name == prefix || (name.startsWith(prefix) && name[prefix.length].isUpperCase())
            }

        private val REASONED_ESCAPE = Regex("""@OpenToAllMembers\s*\(\s*(reason\s*=\s*)?"[^"]*\S[^"]*"\s*,?\s*\)""")

        /** `Class.method @ path` for every override that writes ungated without a reasoned escape. */
        fun findOffenders(scope: KoScope): List<String> =
            scope
                .classes()
                .filter { it.name.endsWith("ServiceImpl") }
                .flatMap { cls ->
                    cls
                        .functions()
                        .asSequence()
                        .filter { it.hasOverrideModifier }
                        .filterNot { fn ->
                            isReadName(fn.name) && WRITE_MARKERS.none { it.containsMatchIn(stripComments(fn.text)) }
                        }.filterNot { fn -> REASONED_ESCAPE.containsMatchIn(stripComments(fn.text)) }
                        .filterNot { fn ->
                            val body = stripComments(fn.text)
                            GATE_CALLS.any { it in body } || ADMIN_DENIAL_SHAPE.containsMatchIn(body)
                        }.map { fn -> "${cls.name}.${fn.name} @ ${cls.path}" }
                        .toList()
                }
    }
}
