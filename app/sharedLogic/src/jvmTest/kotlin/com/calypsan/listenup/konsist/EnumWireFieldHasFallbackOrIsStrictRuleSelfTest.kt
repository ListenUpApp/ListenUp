package com.calypsan.listenup.konsist

import com.calypsan.listenup.konsist.EnumWireFieldHasFallbackOrIsStrictRule.Companion.classify
import com.calypsan.listenup.konsist.EnumWireFieldHasFallbackOrIsStrictRule.Companion.enumsNamedBy
import com.calypsan.listenup.konsist.EnumWireFieldHasFallbackOrIsStrictRule.Companion.hasCustomSerializer
import com.calypsan.listenup.konsist.EnumWireFieldHasFallbackOrIsStrictRule.ContractType
import com.calypsan.listenup.konsist.EnumWireFieldHasFallbackOrIsStrictRule.Verdict
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Self-test for [EnumWireFieldHasFallbackOrIsStrictRule]'s matcher.
 *
 * **Why it exists.** The rule's own test asserts the production tree is *clean*, so it passes whether
 * the matcher works or not. Plant each shape the rule must catch and each it must leave alone.
 */
class EnumWireFieldHasFallbackOrIsStrictRuleSelfTest :
    FunSpec({
        val declarations =
            listOf(
                ContractType("InviteStatus", "api.dto.invite.InviteStatus", isEnum = true, isLenient = false),
                ContractType("ContributorRole", "api.dto.ContributorRole", isEnum = true, isLenient = true),
                ContractType("BookField", "api.metadata.BookField", isEnum = true, isLenient = false),
                ContractType("BookFieldValue", "api.metadata.BookFieldValue", isEnum = false, isLenient = false),
                ContractType("MatchTier", "api.dto.imports.MatchTier", isEnum = true, isLenient = true),
                ContractType("MatchTier", "api.dto.match.MatchTier", isEnum = true, isLenient = false),
                ContractType("ChapterSource", "api.sync.ChapterSource", isEnum = true, isLenient = false),
                ContractType("ChapterSource", "domain.embeddedmeta.ChapterSource", isEnum = false, isLenient = false),
            )

        fun verdict(
            type: String,
            hasDefault: Boolean = false,
            hasOwnSerializer: Boolean = false,
            packageName: String = "api.dto",
            imports: List<String> = emptyList(),
        ) = classify(type, hasDefault, hasOwnSerializer, enumsNamedBy(type, declarations, packageName, imports))

        context("a field with nowhere for an unknown literal to land needs a decision") {
            test("a bare enum without a default") { verdict("InviteStatus") shouldBe Verdict.NEEDS_DECISION }
            test("a nullable enum without a default") { verdict("InviteStatus?") shouldBe Verdict.NEEDS_DECISION }
            test("a list of a strict enum") { verdict("List<InviteStatus>") shouldBe Verdict.NEEDS_DECISION }
            test("a defaulted list of a strict enum — coercion does not reach elements") {
                verdict("List<InviteStatus>", hasDefault = true) shouldBe Verdict.NEEDS_DECISION
            }
            test("a defaulted map keyed by a strict enum — coercion does not reach keys") {
                verdict("Map<BookField, String>", hasDefault = true) shouldBe Verdict.NEEDS_DECISION
            }
            test("a map mixing a lenient and a strict enum") {
                verdict("Map<ContributorRole, InviteStatus>") shouldBe Verdict.NEEDS_DECISION
            }
            test("a flow of a strict enum, as an RPC returns it") {
                verdict("Flow<RpcEvent<InviteStatus>>") shouldBe Verdict.NEEDS_DECISION
            }
        }

        context("a field with a fallback passes") {
            test("a defaulted bare enum") { verdict("InviteStatus", hasDefault = true) shouldBe Verdict.HAS_FALLBACK }
            test("a nullable enum defaulted to null") { verdict("InviteStatus?", hasDefault = true) shouldBe Verdict.HAS_FALLBACK }
            test("a defaulted enum written with its package") {
                verdict("api.dto.invite.InviteStatus", hasDefault = true) shouldBe Verdict.HAS_FALLBACK
            }
            test("an enum with its own lenient serializer") { verdict("ContributorRole") shouldBe Verdict.HAS_FALLBACK }
            test("a list of a lenient enum") { verdict("List<ContributorRole>") shouldBe Verdict.HAS_FALLBACK }
            test("a map carrying its own serializer") {
                verdict("Map<BookField, String>", hasOwnSerializer = true) shouldBe Verdict.HAS_FALLBACK
            }
        }

        context("a type that names no enum is not this rule's business") {
            listOf("String", "List<String>", "BookFieldValue", "List<BookWithContributorRole>", "InviteStatusEvent?").forEach { type ->
                test(type) { verdict(type) shouldBe Verdict.NOT_AN_ENUM_FIELD }
            }
        }

        context("a simple name declared twice is told apart by package and imports") {
            test("the same-package enum wins") {
                verdict("MatchTier", packageName = "api.dto.imports") shouldBe Verdict.HAS_FALLBACK
                verdict("MatchTier", packageName = "api.dto.match") shouldBe Verdict.NEEDS_DECISION
            }
            test("an import wins over the package") {
                verdict("MatchTier", packageName = "api.dto.imports", imports = listOf("api.dto.match.MatchTier")) shouldBe
                    Verdict.NEEDS_DECISION
            }
            test("an unresolvable name is strict unless every enum of that name is lenient") {
                verdict("MatchTier", packageName = "api") shouldBe Verdict.NEEDS_DECISION
            }
            test("a name that resolves to a sealed interface is not an enum field") {
                verdict("ChapterSource", packageName = "domain.embeddedmeta") shouldBe Verdict.NOT_AN_ENUM_FIELD
                verdict("ChapterSource", imports = listOf("domain.embeddedmeta.ChapterSource")) shouldBe Verdict.NOT_AN_ENUM_FIELD
            }
            test("the same name resolving to the enum still is") {
                verdict("ChapterSource", packageName = "api.sync") shouldBe Verdict.NEEDS_DECISION
            }
        }

        context("only @Serializable(with = …) counts as a custom serializer") {
            test("with an argument") {
                hasCustomSerializer("Serializable", "@Serializable(with = EntityKindSerializer::class)") shouldBe true
            }
            test("the bare annotation") { hasCustomSerializer("Serializable", "@Serializable") shouldBe false }
            test("another annotation naming with") { hasCustomSerializer("SerialName", "@SerialName(\"with = x\")") shouldBe false }
        }
    })
