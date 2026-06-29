package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.KoModifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard that catches the most common wire-compat regression in `@Serializable`
 * data classes: a property reorder by an editor refactor.
 *
 * The kotlinx.serialization wire protocol is positional in absence of `@SerialName` —
 * a class with un-tagged properties serializes them in declaration order, and any reorder
 * silently breaks consumers that already deserialized the old order.
 *
 * The lenient form: every `@Serializable data class` must declare `@SerialName` on at least
 * one property OR on the class itself. Classes with fully untagged properties get flagged.
 * Tighten in a future phase if appropriate (e.g., require `@SerialName` on every property).
 */
class StablePropertyOrderRule :
    FunSpec({
        test("@Serializable data classes tag at least one property or the class with @SerialName") {
            val offenders =
                productionScope()
                    .classes()
                    .filter { it.path.contains("/commonMain/") }
                    .filter { klass ->
                        klass.annotations.any { it.name == "Serializable" } &&
                            klass.hasModifier(KoModifier.DATA)
                    }.filter { klass ->
                        val classTagged = klass.annotations.any { it.name == "SerialName" }
                        val anyPropertyTagged =
                            klass.primaryConstructor
                                ?.parameters
                                ?.any { p -> p.annotations.any { it.name == "SerialName" } } == true
                        !classTagged && !anyPropertyTagged
                    }.map { "${it.fullyQualifiedName} @ ${it.path}" }

            offenders.shouldBeEmpty()
        }
    })
