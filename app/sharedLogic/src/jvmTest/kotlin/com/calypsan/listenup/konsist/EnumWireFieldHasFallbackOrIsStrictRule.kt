package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.KoModifier
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Phones update on their own schedule, so a newer server will sooner or later send an enum literal
 * an installed app has never heard of. kotlinx.serialization then throws and the WHOLE response
 * fails to decode: one new invite status and the invite list is an error screen.
 *
 * This rule makes that a decision instead of an accident. Every enum-typed wire field in `:contract`
 * — a primary-constructor property of a `@Serializable` class whose written type names a `:contract`
 * enum, bare, nullable, or inside a `List`/`Set`/`Map` — must be one of:
 *
 * 1. a bare (optionally nullable) enum **with a default**, which `coerceInputValues` substitutes for
 *    an unknown literal ([DefaultedEnumWireFieldIsAlwaysEncodedRule] then forces `@EncodeDefault`).
 *    A default does NOT rescue a collection: coercion never reaches a list element or a map key;
 * 2. of an enum annotated `@Serializable(with = …)` — a lenient serializer on the enum itself;
 * 3. carrying its own `@Serializable(with = …)` — the map-key serializers that drop an unknown key;
 * 4. listed in [STRICT_BY_DECISION] with the reason an unknown value should fail the call.
 *
 * The return types of `@Rpc` service functions get the same treatment: an enum that is itself the
 * response (or the element of the returned `Flow`) has no property to default, so only (2) or (4)
 * can cover it.
 *
 * **This is a name match.** A property counts as enum-typed when its written type contains an enum's
 * simple name as a whole word, so a typealias hiding an enum is not seen. A simple name declared more
 * than once in `:contract` (`MatchTier` is two enums; `ChapterSource` is an enum and a sealed
 * interface) is told apart by the file's imports and package; see [enumsNamedBy].
 *
 * **Not covered:** bare-enum RPC PARAMETERS a client sends (`TargetedMatch`, `PushPlatform`,
 * `CodecCapability`). A newer client talking to an older server is a different problem with a
 * different fix. Nor is an unknown SUBCLASS of a sealed wire type; that is polymorphism, not an enum.
 */
class EnumWireFieldHasFallbackOrIsStrictRule :
    FunSpec({
        val contractClasses =
            productionScope()
                .classes(includeNested = true)
                .filter { "/contract/src/" in it.path && "/commonMain/" in it.path }
        val contractInterfaces =
            productionScope()
                .interfaces(includeNested = true)
                .filter { "/contract/src/" in it.path && "/commonMain/" in it.path }

        val contractTypes =
            contractClasses.map { klass ->
                val isEnum = klass.hasModifier(KoModifier.ENUM)
                ContractType(
                    name = klass.name,
                    fullyQualifiedName = klass.fullyQualifiedName ?: klass.name,
                    isEnum = isEnum,
                    isLenient = isEnum && klass.annotations.any { hasCustomSerializer(it.name, it.text) },
                )
            } +
                contractInterfaces.map {
                    ContractType(it.name, it.fullyQualifiedName ?: it.name, isEnum = false, isLenient = false)
                }

        val wireFields =
            contractClasses
                .filter { klass -> !klass.hasModifier(KoModifier.ENUM) && klass.annotations.any { it.name == "Serializable" } }
                .flatMap { klass ->
                    val packageName = klass.packagee?.name.orEmpty()
                    val owner = (klass.fullyQualifiedName ?: klass.name).removePrefix(packageName).removePrefix(".")
                    val imports = klass.containingFile.imports.map { it.name }
                    klass.primaryConstructor?.parameters.orEmpty().mapNotNull { p ->
                        val verdict =
                            classify(
                                typeText = p.type.text,
                                hasDefault = p.hasDefaultValue(),
                                hasOwnSerializer = p.annotations.any { hasCustomSerializer(it.name, it.text) },
                                named = enumsNamedBy(p.type.text, contractTypes, packageName, imports),
                            )
                        if (verdict == Verdict.NOT_AN_ENUM_FIELD) null else Triple("$owner.${p.name}", p.type.text, verdict)
                    }
                }

        val rpcReturns =
            contractInterfaces
                .filter { it.hasAnnotationWithName("Rpc") }
                .flatMap { service ->
                    val packageName = service.packagee?.name.orEmpty()
                    val imports = service.containingFile.imports.map { it.name }
                    service.functions().mapNotNull { function ->
                        val type = function.returnType?.text ?: return@mapNotNull null
                        val verdict =
                            classify(
                                typeText = type,
                                hasDefault = false,
                                hasOwnSerializer = false,
                                named = enumsNamedBy(type, contractTypes, packageName, imports),
                            )
                        if (verdict == Verdict.NOT_AN_ENUM_FIELD) null else Triple("${service.name}.${function.name}", type, verdict)
                    }
                }

        test("every enum-typed wire field in :contract has a fallback or is strict on purpose") {
            assertScopeNotEmpty(
                contractClasses,
                expectedMin = 200,
                why = "every :contract commonMain class — an empty set means the module dropped out",
            )
            assertScopeNotEmpty(
                contractTypes.filter { it.isEnum },
                expectedMin = 20,
                why = "the :contract enum classes — property types are matched against these names",
            )
            assertScopeNotEmpty(
                contractTypes.filter { it.isLenient },
                expectedMin = 4,
                why = "enums with their own lenient serializer — zero means the annotation match went blind",
            )
            assertScopeNotEmpty(
                wireFields,
                expectedMin = 60,
                why = "enum-typed wire properties — the population this rule polices",
            )
            assertScopeNotEmpty(
                wireFields.filter { (_, _, verdict) -> verdict == Verdict.HAS_FALLBACK },
                expectedMin = 30,
                why = "enum-typed wire properties recognised as having a fallback — zero means the matcher rejects everything",
            )
            assertScopeNotEmpty(
                rpcReturns,
                expectedMin = 2,
                why = "@Rpc functions that return a bare :contract enum — the population with no property to default",
            )

            val offenders =
                (wireFields + rpcReturns)
                    .filter { (name, _, verdict) -> verdict == Verdict.NEEDS_DECISION && name !in STRICT_BY_DECISION }
                    .map { (name, type, _) ->
                        "$name: $type — give it a default plus @EncodeDefault, a lenient serializer, or a " +
                            "STRICT_BY_DECISION entry saying why an unknown value should fail the call"
                    }.sorted()

            withClue(offenders.joinToString("\n", prefix = "\n")) { offenders.shouldBeEmpty() }
        }

        test("STRICT_BY_DECISION lists only fields that are still strict, each with a reason") {
            assertScopeNotEmpty(
                STRICT_BY_DECISION.keys,
                expectedMin = 5,
                why = "the strict-by-decision list — the Match-details round trip is strict on purpose",
            )

            val stillStrict =
                (wireFields + rpcReturns)
                    .filter { (_, _, verdict) -> verdict == Verdict.NEEDS_DECISION }
                    .map { (name, _, _) -> name }
                    .toSet()
            val stale =
                STRICT_BY_DECISION.keys
                    .filter { it !in stillStrict }
                    .map { "$it: no longer a strict enum-typed wire field — remove the entry" }
            val unexplained =
                STRICT_BY_DECISION
                    .filterValues { it.isBlank() }
                    .keys
                    .map { "$it: needs a reason" }

            withClue((stale + unexplained).joinToString("\n", prefix = "\n")) { (stale + unexplained).shouldBeEmpty() }
        }
    }) {
    /** What [classify] decided about one written type. */
    enum class Verdict { NOT_AN_ENUM_FIELD, HAS_FALLBACK, NEEDS_DECISION }

    /**
     * One `:contract` class or interface: its simple and qualified names, whether it is an enum, and
     * whether that enum carries a lenient serializer.
     */
    data class ContractType(
        val name: String,
        val fullyQualifiedName: String,
        val isEnum: Boolean,
        val isLenient: Boolean,
    )

    companion object {
        private const val MATCH_DETAILS =
            "The Match-details round trip is built for a specific vocabulary; an unknown value means the app " +
                "is too old for the feature, and a typed failure of that one call is the honest result."

        /**
         * Enum-typed wire fields that deliberately have NO fallback, keyed `Class.property` (nested
         * classes spelled out: `Outer.Inner.property`) with the reason. A too-old client decoding an
         * unknown literal here gets a typed `AppResult.Failure` for that one call;
         * `RpcChannelUnknownEnumE2ETest` pins that.
         */
        val STRICT_BY_DECISION: Map<String, String> =
            mapOf(
                "AppliedChange.Field.field" to MATCH_DETAILS,
                "FieldReview.field" to MATCH_DETAILS,
                "FieldReview.state" to MATCH_DETAILS,
                "BiographyReview.state" to MATCH_DETAILS,
                "SourceStatus.Unavailable.reason" to MATCH_DETAILS,
                "RegionContext.origin" to MATCH_DETAILS,
                "MatchReason.DifferentEdition.format" to MATCH_DETAILS,
                "BookCandidate.tier" to MATCH_DETAILS,
                "PersonCandidate.tier" to MATCH_DETAILS,
                "SearchStep.Identifier.kind" to MATCH_DETAILS,
                "FieldDecision.field" to
                    "Sent by the client: the server must refuse a field it does not know rather than apply a guess.",
            )

        /** True for `@Serializable(with = …)`; false for the bare `@Serializable`. */
        fun hasCustomSerializer(
            annotationName: String,
            annotationText: String,
        ): Boolean = annotationName == "Serializable" && Regex("""\bwith\s*=""").containsMatchIn(annotationText)

        /**
         * The `:contract` enums that [typeText], written in a file in [packageName] with [imports], names.
         *
         * A simple name declared once means that declaration. A name declared more than once resolves to
         * the imported declaration, else the same-package one; a name that resolves to something that is
         * not an enum names no enum. If nothing picks one out, every enum of that name is returned, so an
         * ambiguity can never hide a strict enum behind a lenient namesake.
         */
        fun enumsNamedBy(
            typeText: String,
            declarations: List<ContractType>,
            packageName: String,
            imports: List<String>,
        ): List<ContractType> =
            declarations
                .groupBy { it.name }
                .filterKeys { name -> Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(typeText) }
                .values
                .flatMap { sameName ->
                    val resolved =
                        sameName.singleOrNull()
                            ?: sameName.firstOrNull { it.fullyQualifiedName in imports }
                            ?: sameName.firstOrNull { it.fullyQualifiedName == "$packageName.${it.name}" }
                    (resolved?.let(::listOf) ?: sameName).filter { it.isEnum }
                }

        /**
         * Decides whether a property (or return type) written as [typeText] and naming the enums
         * [named] has somewhere for an unknown literal to land.
         *
         * A default only counts when the type is the enum itself (optionally nullable): inside a
         * collection, `coerceInputValues` never sees the element.
         */
        fun classify(
            typeText: String,
            hasDefault: Boolean,
            hasOwnSerializer: Boolean,
            named: List<ContractType>,
        ): Verdict {
            val bareName = typeText.trim().removeSuffix("?").substringAfterLast('.')
            val isBareEnum = named.any { it.name == bareName }
            return when {
                named.isEmpty() -> Verdict.NOT_AN_ENUM_FIELD
                hasOwnSerializer -> Verdict.HAS_FALLBACK
                named.all { it.isLenient } -> Verdict.HAS_FALLBACK
                isBareEnum && hasDefault -> Verdict.HAS_FALLBACK
                else -> Verdict.NEEDS_DECISION
            }
        }
    }
}
