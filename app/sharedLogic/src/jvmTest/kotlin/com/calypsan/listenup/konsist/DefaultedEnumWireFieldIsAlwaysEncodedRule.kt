package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.KoModifier
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Both JSON configurations in `:contract` leave `encodeDefaults` off, so a property holding its
 * declared default is omitted from the encoded JSON. That is harmless for a property that always
 * had a default, and a break when a default is added later: a build that predates it treats the key
 * as required and fails to decode the whole object.
 *
 * This pins the fix for the enum-typed case (the one the additive-enum-fallback pattern creates):
 * every constructor property of a `@Serializable` class in `:contract` whose declared type is a
 * non-nullable `:contract` enum and which has a default value carries `@EncodeDefault`.
 */
class DefaultedEnumWireFieldIsAlwaysEncodedRule :
    FunSpec({
        test("a defaulted enum-typed wire property in :contract carries @EncodeDefault") {
            val contractClasses =
                productionScope()
                    .classes(includeNested = true)
                    .filter { "/contract/src/" in it.path && "/commonMain/" in it.path }

            assertScopeNotEmpty(
                contractClasses,
                expectedMin = 200,
                why = "every :contract commonMain class — an empty set means the module dropped out",
            )

            val enumNames =
                contractClasses
                    .filter { it.hasModifier(KoModifier.ENUM) }
                    .map { it.name }
                    .toSet()

            assertScopeNotEmpty(
                enumNames,
                expectedMin = 20,
                why = "the :contract enum classes — property types are matched against these names",
            )

            val defaultedEnumProperties =
                contractClasses
                    .filter { klass -> klass.annotations.any { it.name == "Serializable" } }
                    .flatMap { klass ->
                        klass.primaryConstructor
                            ?.parameters
                            .orEmpty()
                            .filter { p -> p.hasDefaultValue() && !p.type.isNullable && p.type.name in enumNames }
                            .map { p -> klass to p }
                    }

            assertScopeNotEmpty(
                defaultedEnumProperties,
                expectedMin = 5,
                why = "defaulted enum-typed wire properties — the population this rule polices",
            )

            val offenders =
                defaultedEnumProperties
                    .filter { (_, p) -> p.annotations.none { it.name == "EncodeDefault" } }
                    .map { (klass, p) ->
                        "${klass.fullyQualifiedName ?: klass.name}.${p.name} @ ${klass.path}: add " +
                            "@EncodeDefault(EncodeDefault.Mode.ALWAYS) so builds that predate the default still find the key"
                    }

            withClue(offenders.joinToString("\n", prefix = "\n")) { offenders.shouldBeEmpty() }
        }
    })
