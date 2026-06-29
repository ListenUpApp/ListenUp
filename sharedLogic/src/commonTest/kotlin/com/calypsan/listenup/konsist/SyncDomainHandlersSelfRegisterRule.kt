package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Pins the registration discipline: every `SyncDomainHandler<*>` impl's
 * `init { }` block calls `registry.register(this)` (where `registry` is a
 * constructor parameter of type `ClientSyncDomainRegistry`).
 *
 * Heuristic: text-scan each impl class for the pattern `register(this)` after
 * stripping comments. False positive shape would require the class to mention
 * `register(this)` in something other than init — vanishingly unlikely.
 */
class SyncDomainHandlersSelfRegisterRule :
    FunSpec({
        test("SyncDomainHandler impls call registry.register(this) in their init block") {
            val offenders =
                productionScope()
                    .classes()
                    .filter { it.parents().any { p -> p.name == "SyncDomainHandler" } }
                    .filterNot { cls ->
                        val stripped = stripComments(cls.text)
                        stripped.contains("register(this)")
                    }.map { "${it.name} in ${it.path}" }

            offenders.shouldBeEmpty()
        }
    })

private fun stripComments(source: String): String =
    source
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
