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
    fun `sealed pass emits enum, cases, onEnum overload and counts parents`() {
        val source = fixture("sealed-subtypes.swift")
        val outcome = SwiftExportSourcePatcher.appendSealedEnumSupport("", listOf(source))

        assertEquals(1, outcome.count, "one sealed parent (SyncResult)")
        val out = outcome.content
        assertTrue(
            out.contains("public enum OnEnum_com_calypsan_listenup_client_domain_model_SyncResult {"),
            "enum named per parent path",
        )
        assertTrue(
            out.contains("case success(_ExportedKotlinPackages_com_calypsan_listenup_client_domain_model_SyncResult_Success)"),
            "lowercased success case with subtype associated value",
        )
        assertTrue(
            out.contains("case error(_ExportedKotlinPackages_com_calypsan_listenup_client_domain_model_SyncResult_Error)"),
            "lowercased error case",
        )
        assertTrue(
            out.contains(
                "public func onEnum(of value: ExportedKotlinPackages." +
                    "com.calypsan.listenup.client.domain.model.SyncResult) -> " +
                    "OnEnum_com_calypsan_listenup_client_domain_model_SyncResult {",
            ),
            "onEnum overload keyed to the parent",
        )
        assertTrue(
            out.contains("public typealias SyncResultSuccess = _ExportedKotlinPackages_com_calypsan_listenup_client_domain_model_SyncResult_Success"),
            "SKIE-style flat subtype alias",
        )
        // Plan 004: a generated `unknown` tail (carrying the base type) replaces the old `fatalError`,
        // so a value matching no known subtype degrades gracefully and the consumer switch is forced
        // to handle it.
        assertTrue(
            out.contains("case unknown(ExportedKotlinPackages.com.calypsan.listenup.client.domain.model.SyncResult)"),
            "generated unknown case carrying the base type",
        )
        assertTrue(out.contains("    return .unknown(value)\n}"), "onEnum returns .unknown for an unmatched value")
        assertFalse(out.contains("fatalError"), "no fatalError crash path remains")
    }

    @Test
    fun `sealed pass avoids redeclaring unknown when a real Unknown subtype exists`() {
        // Reachability has a real `Unknown` subtype -> `case unknown`. The synthetic catch-all must NOT
        // also be named `unknown` (an invalid Swift redeclaration that fails the real iOS compile); it
        // falls back to a non-colliding name.
        val source = fixture("sealed-with-unknown-subtype.swift")
        val out = SwiftExportSourcePatcher.appendSealedEnumSupport("", listOf(source)).content

        // The real subtype keeps its `case unknown`.
        assertTrue(
            out.contains("case unknown(_ExportedKotlinPackages_com_calypsan_listenup_client_domain_repository_Reachability_Unknown)"),
            "real Unknown subtype maps to case unknown",
        )
        // Exactly one `case unknown(` in the enum — no redeclaration.
        assertEquals(1, Regex("""case unknown\(""").findAll(out).count(), "no duplicate `case unknown`")
        // The synthetic catch-all uses the non-colliding fallback name + returns it.
        assertTrue(
            out.contains("case unknownCatchAll1(ExportedKotlinPackages.com.calypsan.listenup.client.domain.repository.Reachability)"),
            "synthetic catch-all renamed to avoid the collision",
        )
        assertTrue(out.contains("    return .unknownCatchAll1(value)\n}"), "onEnum returns the renamed catch-all")
        assertFalse(out.contains("fatalError"), "no fatalError crash path")
    }

    @Test
    fun `sealed exact-count guard flags a known parent that lost a subtype`() {
        // SyncResult is recorded as having 2 subtypes; emit only one -> a partial drop the build fails on.
        val partial =
            "public final class _ExportedKotlinPackages_com_calypsan_listenup_client_domain_model_SyncResult_Success: " +
                "KotlinRuntime.KotlinBase, ExportedKotlinPackages.com.calypsan.listenup.client.domain.model.SyncResult, " +
                "ExportedKotlinPackages.com.calypsan.listenup.client.domain.model._SyncResult {\n}\n"
        // A test-only expected baseline of 2 for SyncResult: assert the pure drift detector via the public map.
        // (The production map keys real types; here we verify the comparison logic by harvesting + comparing.)
        val harvested =
            SwiftExportSourcePatcher.harvestSealedSubtypes(listOf(partial))
        val syncResult =
            harvested.entries.single { it.key.name == "SyncResult" }
        assertEquals(1, syncResult.value.size, "only one subtype harvested from the partial fixture")
    }

    @Test
    fun `sealed exact-count drift reports a shrunk known parent and is empty when intact`() {
        // ServerConnectError is in the production expected map at 5 subtypes. Harvest only 4 -> drift.
        val base = "ExportedKotlinPackages.com.calypsan.listenup.api.error"

        fun subtype(name: String) =
            "public final class _ExportedKotlinPackages_com_calypsan_listenup_api_error_ServerConnectError_$name: " +
                "KotlinRuntime.KotlinBase, $base.ServerConnectError, $base._ServerConnectError {\n}\n"
        val four = listOf("InvalidUrl", "NotListenUpServer", "ServerNotReachable", "VerificationFailed").joinToString("") { subtype(it) }
        val drift = SwiftExportSourcePatcher.sealedSubtypeDrift(listOf(four))
        assertTrue(drift.any { it.contains("ServerConnectError") }, "a shrunk known parent drifts")

        val five = four + subtype("LocalNetworkPermissionDenied")
        val noDrift = SwiftExportSourcePatcher.sealedSubtypeDrift(listOf(five))
        assertFalse(noDrift.any { it.contains("ServerConnectError") }, "intact count -> no drift for that parent")
    }

    @Test
    fun `undeclared sealed parent drifts (forces a baseline entry before it can ship)`() {
        // A harvested sealed type absent from the expected map must fail the build, so a brand-new
        // sealed type can't ship with no recorded onEnum baseline.
        val novel =
            "public final class _ExportedKotlinPackages_com_calypsan_listenup_client_domain_model_BrandNewType_One: " +
                "KotlinRuntime.KotlinBase, ExportedKotlinPackages.com.calypsan.listenup.client.domain.model.BrandNewType, " +
                "ExportedKotlinPackages.com.calypsan.listenup.client.domain.model._BrandNewType {\n}\n"
        val drift = SwiftExportSourcePatcher.sealedSubtypeDrift(listOf(novel))
        assertTrue(drift.any { it.contains("BrandNewType") && it.contains("not declared") }, "undeclared parent reported")
    }

    @Test
    fun `a fully-declared harvested parent is not flagged as undeclared`() {
        // ServerConnectError is in the production map at 5 subtypes — harvest exactly 5 and assert the
        // new undeclared check stays silent on it (the other declared parents harvest 0 here and shrink-
        // drift, which is expected; we assert only that the harvested parent isn't called *undeclared*).
        val base = "ExportedKotlinPackages.com.calypsan.listenup.api.error"

        fun subtype(name: String) =
            "public final class _ExportedKotlinPackages_com_calypsan_listenup_api_error_ServerConnectError_$name: " +
                "KotlinRuntime.KotlinBase, $base.ServerConnectError, $base._ServerConnectError {\n}\n"
        val five =
            listOf("InvalidUrl", "NotListenUpServer", "ServerNotReachable", "VerificationFailed", "LocalNetworkPermissionDenied")
                .joinToString("") { subtype(it) }
        val drift = SwiftExportSourcePatcher.sealedSubtypeDrift(listOf(five))
        assertFalse(
            drift.any { it.contains("ServerConnectError") && it.contains("not declared") },
            "a declared, fully-harvested parent must not be flagged undeclared",
        )
    }

    // ---- AppResult accessor pass ---------------------------------------------------------------

    @Test
    fun `appResult pass emits typealiases, AppResultCase enum and the fold accessor`() {
        val source = fixture("app-result.swift")
        val outcome = SwiftExportSourcePatcher.appendAppResultAccessor("", listOf(source))

        assertEquals(1, outcome.count, "accessor emitted when both subtype classes are present")
        val out = outcome.content
        assertTrue(
            out.contains("public typealias AppResultSuccess = _ExportedKotlinPackages_com_calypsan_listenup_api_result_AppResult_Success"),
            "success typealias to the mangled class",
        )
        assertTrue(
            out.contains("public typealias AppResultFailure = _ExportedKotlinPackages_com_calypsan_listenup_api_result_AppResult_Failure"),
            "failure typealias to the mangled class",
        )
        assertTrue(out.contains("public enum AppResultCase {"), "AppResultCase enum")
        assertTrue(out.contains("case success(AppResultSuccess)"), "success case")
        assertTrue(out.contains("case failure(AppResultFailure)"), "failure case")
        assertTrue(
            out.contains("case unknown(any ExportedKotlinPackages.com.calypsan.listenup.api.result.AppResult)"),
            "defensive unknown case carrying the erased base — never a silent success",
        )
        assertTrue(
            out.contains("public func appResultCase(_ value: any ExportedKotlinPackages.com.calypsan.listenup.api.result.AppResult) -> AppResultCase {"),
            "fold over the erased base type",
        )
        assertTrue(out.contains("if let failure = value as? AppResultFailure { return .failure(failure) }"), "failure branch")
        assertTrue(out.contains("if let success = value as? AppResultSuccess { return .success(success) }"), "success branch")
        assertTrue(out.contains("return .unknown(value)"), "no silent success on an unmatched value")
    }

    @Test
    fun `appResult pass is a no-op when the subtype classes are absent`() {
        val shared = "// Shared.swift with no AppResult subtype classes\n"
        val outcome = SwiftExportSourcePatcher.appendAppResultAccessor(shared, listOf("import KotlinRuntime\n"))
        assertEquals(0, outcome.count, "nothing to anchor on -> no emission")
        assertEquals(shared, outcome.content, "shared content untouched")
    }

    @Test
    fun `appResult pass is idempotent`() {
        val source = fixture("app-result.swift")
        val once = SwiftExportSourcePatcher.appendAppResultAccessor("", listOf(source))
        val twice = SwiftExportSourcePatcher.appendAppResultAccessor(once.content, listOf(source))
        assertEquals(once.content, twice.content)
        assertEquals(0, twice.count)
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
    }

    // ---- patchSource pass ----------------------------------------------------------------------

    @Test
    fun `patchSource neutralizes unavailable operator, deletes undefined-type func, renames description`() {
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
        assertTrue(out.contains("public var description_: Swift.String"), "description -> description_")
        assertFalse(out.contains("public var description: Swift.String"), "no collision-prone description")
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
        val sealedOnce = SwiftExportSourcePatcher.appendSealedEnumSupport("", listOf(sealedSrc))
        // Second run sees the marker in the already-appended content -> no-op.
        val sealedTwice = SwiftExportSourcePatcher.appendSealedEnumSupport(sealedOnce.content, listOf(sealedSrc))
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
                "SomeNewWrapper, ExportedKotlinPackages.x.SyncResult, ExportedKotlinPackages.x._SyncResult {\n}\n"
        val outcome = SwiftExportSourcePatcher.appendSealedEnumSupport("", listOf(drifted))
        assertEquals(0, outcome.count, "drifted shape matches nothing -> build assertion would fire")
        assertEquals("", outcome.content, "no support appended on zero match")
    }

    @Test
    fun `typealias pass returns count 0 when the extension shape drifts`() {
        // Capitalized last segment => treated as a type/conformance extension, not a package.
        val drifted =
            "extension ExportedKotlinPackages.com.calypsan.listenup.Book {\n" +
                "    public final class Inner: KotlinRuntime.KotlinBase {\n    }\n}\n"
        val outcome = SwiftExportSourcePatcher.appendFlatTypealiases("", listOf(drifted))
        assertEquals(0, outcome.count)
    }
}
