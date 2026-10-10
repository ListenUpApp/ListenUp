package com.calypsan.listenup.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden-fixture tests for [SwiftExportSourcePatcher]. Mirrors `LocalizationGeneratorTest`: feed a
 * captured-shape Swift snippet, assert the rewritten string + the match count. The fixtures pin the
 * exact emitted shapes the regexes target so a Swift-Export/Kotlin bump that shifts codegen turns a
 * transform red here (and would fire the build's fail-fast count assertions).
 */
class SwiftExportSourcePatcherTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/swiftexport/$name")) { "missing fixture $name" }
            .bufferedReader()
            .readText()

    // ---- sealed-enum pass ----------------------------------------------------------------------

    @Test
    fun `sealed pass emits a flat alias per subtype and counts parents`() {
        val source = fixture("sealed-subtypes.swift")
        val outcome = SwiftExportSourcePatcher.appendSealedSubtypeAliases("", listOf(source))

        assertEquals(1, outcome.count, "one sealed parent (SyncResult)")
        val out = outcome.content
        assertTrue(
            out.contains(
                "public typealias SyncResultSuccess = _ExportedKotlinPackages_com_calypsan_listenup_client_domain_model_SyncResult_Success",
            ),
            "SKIE-style flat subtype alias",
        )
        assertTrue(
            out.contains(
                "public typealias SyncResultError = _ExportedKotlinPackages_com_calypsan_listenup_client_domain_model_SyncResult_Error",
            ),
            "one alias per subtype",
        )
        // Kotlin 2.4.20 emits `sealedType()` natively; the hand-rolled enum must not come back.
        assertFalse(out.contains("enum OnEnum_"), "no hand-rolled sealed enum")
        assertFalse(out.contains("onEnum("), "no onEnum overload")
        assertFalse(out.contains("unknown"), "no synthetic unknown case")
    }

    // ---- flat-typealias pass -------------------------------------------------------------------

    @Test
    fun `typealias pass emits flat aliases for package-level types and counts them`() {
        val source = fixture("package-types.swift")
        val outcome = SwiftExportSourcePatcher.appendFlatTypealiases("", listOf(source))

        // Book + Contributor are package-level (depth 1); Book.Companion is nested + `Companion` is skipped.
        assertEquals(2, outcome.count)
        val out = outcome.content
        assertTrue(
            out.contains("public typealias Book = ExportedKotlinPackages.com.calypsan.listenup.client.domain.model.Book"),
        )
        assertTrue(
            out.contains("public typealias Contributor = ExportedKotlinPackages.com.calypsan.listenup.client.domain.model.Contributor"),
        )
        assertFalse(out.contains("typealias Companion"), "Companion is excluded")
        // Kotlin 2.4.20 made the sealed marker protocols `public protocol __<Name>`; they are
        // generator plumbing, not API, and must not widen the reviewed export surface.
        assertFalse(out.contains("typealias __Book"), "underscore-prefixed generator internals are excluded")
    }

    // ---- patchSource pass ----------------------------------------------------------------------

    @Test
    fun `patchSource neutralizes unavailable operator and deletes undefined-type func`() {
        val source = fixture("patch-source.swift")
        val outcome = SwiftExportSourcePatcher.patchSource(source, module = "Shared")

        assertEquals(1, outcome.count, "the file changed")
        val out = outcome.content
        assertTrue(
            out.contains("""fatalError("swift-export: unavailable operator")"""),
            "unavailable-operator body neutralized",
        )
        assertFalse(out.contains("this._plus"), "original helper call removed")
        assertFalse(out.contains("func Format("), "undefined-type func deleted whole")
        assertFalse(out.contains("_ExportedKotlinPackages_DateTimeFormatBuilder_WithDate"), "no dangling ref")
    }

    @Test
    fun `patchSource drops an spi stub that duplicates a real implementation in the same extension`() {
        val out = SwiftExportSourcePatcher.patchSource(fixture("patch-source.swift"), module = "Shared").content

        assertEquals(1, Regex("""func decodeSequentially\(""").findAll(out).count(), "one declaration survives")
        assertTrue(out.contains("decodeSequentially_direct"), "the bridged implementation is the survivor")
        assertFalse(out.contains("'decodeSequentially' is an @_spi requirement"), "the stub is gone")
        assertEquals(
            1,
            Regex(Regex.escape("@_spi(kotlinx\$serialization")).findAll(out).count(),
            "the stub's attribute line went with it",
        )
        // A stub with no twin in its block is the generator's legitimate default for Swift conformers.
        assertTrue(out.contains("'resetReplayCache' is an @_spi requirement"), "a stub-only requirement stays")
    }

    @Test
    fun `patchSource drops a sealed case whose payload names a type the module never emits`() {
        val out = SwiftExportSourcePatcher.patchSource(fixture("patch-source.swift"), module = "Shared").content

        assertFalse(out.contains("DateTimeComponentsFormat.Builder_SealedType"), "the unexported subtype's case is gone")
        assertTrue(out.contains("public enum WithDateTimeComponents_SealedType"), "the enum itself survives (its callers still name it)")
        assertEquals(1, Regex("""case let \.builder\(type\): type\.value""").findAll(out).count(), "only the doomed getter arm went")
        // The control: same case name, but its outer type IS declared in the module.
        assertTrue(out.contains("case builder(ExportedKotlinPackages.kotlinx.datetime.format.DateTimeFormat.Builder_SealedType)"))
    }

    @Test
    fun `patchSource widens a sealed enum's value type when its payload classes do not conform to it`() {
        val out = SwiftExportSourcePatcher.patchSource(fixture("patch-source.swift"), module = "Shared").content

        // Generic sealed type: the erased base is a protocol the subtype classes never adopt.
        assertFalse(
            out.contains("public var value: ExportedKotlinPackages.com.calypsan.listenup.api.result.AppResult {"),
            "the unconformed protocol type is gone",
        )
        assertTrue(out.contains("public var value: KotlinRuntime.KotlinBase {"), "widened to the common base class")
        // The control: a protocol every payload class conforms to stays as the value type.
        assertTrue(out.contains("public var value: ExportedKotlinPackages.x.Bar {"), "a conformed protocol is untouched")
    }

    // ---- camelCase pass ------------------------------------------------------------------------

    @Test
    fun `camelCase rewrites SCREAMING_SNAKE case decls and references, leaves string literals`() {
        val source = fixture("camel-case-enum.swift")
        val outcome = SwiftExportSourcePatcher.camelCaseEnumCases(source)

        assertEquals(1, outcome.count)
        val out = outcome.content
        assertTrue(out.contains("case author"), "AUTHOR -> author decl")
        assertTrue(out.contains("case firstName"), "FIRST_NAME -> firstName decl")
        assertTrue(out.contains("case .author: return"), ".AUTHOR -> .author reference")
        assertTrue(out.contains("case .firstName: return"), ".FIRST_NAME -> .firstName reference")
        // Round-trip string literals (the Kotlin wire name) are untouched.
        assertTrue(out.contains("""return "AUTHOR""""), "string literal preserved")
        assertTrue(out.contains("""return "FIRST_NAME""""), "string literal preserved")
        assertFalse(out.contains("case AUTHOR"), "no SCREAMING_SNAKE decl remains")
    }

    // ---- idempotency ---------------------------------------------------------------------------

    @Test
    fun `each transform is idempotent`() {
        val sealedSrc = fixture("sealed-subtypes.swift")
        val sealedOnce = SwiftExportSourcePatcher.appendSealedSubtypeAliases("", listOf(sealedSrc))
        // Second run sees the marker in the already-appended content -> no-op.
        val sealedTwice = SwiftExportSourcePatcher.appendSealedSubtypeAliases(sealedOnce.content, listOf(sealedSrc))
        assertEquals(sealedOnce.content, sealedTwice.content)
        assertEquals(0, sealedTwice.count)

        val aliasSrc = fixture("package-types.swift")
        val aliasOnce = SwiftExportSourcePatcher.appendFlatTypealiases("", listOf(aliasSrc))
        val aliasTwice = SwiftExportSourcePatcher.appendFlatTypealiases(aliasOnce.content, listOf(aliasSrc))
        assertEquals(aliasOnce.content, aliasTwice.content)
        assertEquals(0, aliasTwice.count)

        val camelOnce = SwiftExportSourcePatcher.camelCaseEnumCases(fixture("camel-case-enum.swift"))
        val camelTwice = SwiftExportSourcePatcher.camelCaseEnumCases(camelOnce.content)
        assertEquals(camelOnce.content, camelTwice.content)
        assertEquals(0, camelTwice.count, "already-camelCased cases don't re-match SCREAMING_SNAKE")

        val patchOnce = SwiftExportSourcePatcher.patchSource(fixture("patch-source.swift"), "Shared")
        val patchTwice = SwiftExportSourcePatcher.patchSource(patchOnce.content, "Shared")
        assertEquals(patchOnce.content, patchTwice.content)
        assertEquals(0, patchTwice.count, "neutralized output has nothing left to patch")
    }

    // ---- drift signal (proves the build's Step-4 assertion would fire) -------------------------

    @Test
    fun `sealed pass returns count 0 when the subtype shape drifts`() {
        // An extra qualifier between KotlinBase and the conformances breaks the regex anchor.
        val drifted =
            "public final class _ExportedKotlinPackages_x_SyncResult_Success: KotlinRuntime.KotlinBase, " +
                "SomeNewWrapper, ExportedKotlinPackages.x.SyncResult, ExportedKotlinPackages.x.__SyncResult {\n}\n"
        val outcome = SwiftExportSourcePatcher.appendSealedSubtypeAliases("", listOf(drifted))
        assertEquals(0, outcome.count, "drifted shape matches nothing -> build assertion would fire")
        assertEquals("", outcome.content, "no support appended on zero match")
    }

    @Test
    fun `typealias pass returns count 0 when the extension shape drifts`() {
        // Capitalized last segment => treated as a type/conformance extension, not a package.
        val drifted =
            """
            extension ExportedKotlinPackages.com.calypsan.listenup.Book {
                public final class Inner: KotlinRuntime.KotlinBase {
                }
            }
            """.trimIndent() + "\n"
        val outcome = SwiftExportSourcePatcher.appendFlatTypealiases("", listOf(drifted))
        assertEquals(0, outcome.count)
    }

    // ---- negative fixtures: shapes the passes could mishandle -----------------------------------

    private val neutralized = """fatalError("swift-export: unavailable operator")"""

    @Test
    fun `an unavailable property does not arm the next available operator`() {
        val out = SwiftExportSourcePatcher.patchSource(fixture("unavailable-property-then-operator.swift"), "Shared").content

        assertTrue(out.contains("this._plus(other: other)"), "the available operator keeps its bridged body")
        assertFalse(out.contains(neutralized), "nothing here is an unavailable operator")
    }

    @Test
    fun `an unavailable typealias does not arm the next available operator`() {
        val out = SwiftExportSourcePatcher.patchSource(fixture("unavailable-typealias-then-operator.swift"), "Shared").content

        assertTrue(out.contains("this._plus(other: other)"), "the available operator keeps its bridged body")
        assertFalse(out.contains(neutralized), "nothing here is an unavailable operator")
    }

    @Test
    fun `a bodiless requirement is kept and only the undefined-type func is deleted`() {
        val out = SwiftExportSourcePatcher.patchSource(fixture("bodiless-func.swift"), "Shared").content

        assertTrue(out.contains("func requirement() -> Swift.Int"), "protocol requirement stays")
        assertTrue(out.contains("func other() -> Swift.Int"), "the requirement ahead of the doomed func stays")
        assertTrue(out.contains("public func plain() -> Swift.Int {\n    return 1\n}"), "ordinary func stays whole")
        assertTrue(out.contains("public protocol Sink {"), "the protocol declaration stays")
        assertFalse(out.contains("func Format("), "the undefined-type func is deleted")
    }

    @Test
    fun `a brace inside a string literal or a comment does not shift the block depth`() {
        val source = fixture("brace-in-literal.swift")

        val aliases = SwiftExportSourcePatcher.appendFlatTypealiases("", listOf(source))
        assertEquals(2, aliases.count, "Alpha and Beta are both package-level")
        assertTrue(aliases.content.contains("public typealias Alpha = "))
        assertTrue(aliases.content.contains("public typealias Beta = "))

        val camel = SwiftExportSourcePatcher.camelCaseEnumCases(source).content
        assertTrue(camel.contains("case gold"), "GOLD is rewritten")
        assertTrue(camel.contains("case silverPlus"), "SILVER_PLUS follows a comment with a brace and is still rewritten")
        assertTrue(camel.contains("case .silverPlus: return"), "its reference is rewritten too")
    }

    @Test
    fun `NONE camel-cases to none, which iOS relies on for OrganizeSeriesPrefix`() {
        val out = SwiftExportSourcePatcher.camelCaseEnumCases(fixture("enum-edge-cases.swift")).content

        assertTrue(out.contains("case none"), "NONE -> none")
        assertTrue(out.contains("case .none: return"), ".NONE -> .none")
        assertTrue(out.contains("""return "NONE""""), "the wire-name literal is untouched")
        assertTrue(out.contains("case bookNDash"), "siblings camel-case as usual")
    }

    @Test
    fun `enumsWithNoneCase names the enums whose NONE case becomes none`() {
        val source = fixture("enum-edge-cases.swift")

        assertEquals(listOf("OrganizeSeriesPrefix"), SwiftExportSourcePatcher.enumsWithNoneCase(source))
        // Still found once the camelCase pass has run, so a rebuild over patched output reports the same.
        val patched = SwiftExportSourcePatcher.camelCaseEnumCases(source).content
        assertEquals(listOf("OrganizeSeriesPrefix"), SwiftExportSourcePatcher.enumsWithNoneCase(patched))
    }

    private fun packageSource(
        namespace: String,
        vararg types: String,
    ): String =
        "extension ExportedKotlinPackages.$namespace {\n" +
            types.joinToString("") { "    public final class $it: KotlinRuntime.KotlinBase {\n    }\n" } +
            "}\n"

    @Test
    fun `a name declared in two sibling client domain packages resolves to the smallest namespace every time`() {
        repeat(50) {
            val sources =
                listOf(
                    packageSource("com.calypsan.listenup.client.domain.beta", "Widget"),
                    packageSource("com.calypsan.listenup.client.domain.alpha", "Widget"),
                )
            val outcome = SwiftExportSourcePatcher.appendFlatTypealiases("", sources)

            assertTrue(
                outcome.content.contains(
                    "public typealias Widget = ExportedKotlinPackages.com.calypsan.listenup.client.domain.alpha.Widget",
                ),
            )
        }
    }

    @Test
    fun `a name declared in two unrelated packages is reported as ambiguous and gets no alias`() {
        val sources =
            listOf(
                packageSource("com.calypsan.listenup.one", "Gadget", "Solo"),
                packageSource("com.calypsan.listenup.two", "Gadget"),
            )
        val outcome = SwiftExportSourcePatcher.appendFlatTypealiases("", sources)

        assertEquals(listOf("Gadget"), SwiftExportSourcePatcher.ambiguousFlatNames(sources))
        assertFalse(outcome.content.contains("typealias Gadget"), "ambiguous name stays qualified")
        assertTrue(outcome.content.contains("typealias Solo"), "the unambiguous neighbour is aliased")
        assertEquals(1, outcome.count)
    }

    @Test
    fun `a single-letter case is left uppercase`() {
        val out = SwiftExportSourcePatcher.camelCaseEnumCases(fixture("enum-edge-cases.swift")).content

        assertTrue(Regex("""(?m)^\s*case A$""").containsMatchIn(out), "case A stays")
        assertTrue(out.contains("case best"), "BEST becomes best")
    }
}
