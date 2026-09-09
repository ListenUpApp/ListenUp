package com.calypsan.listenup.gradle

import java.io.File

/** Result of one patch transform: the rewritten source and how many declarations it touched. */
data class PatchOutcome(
    val content: String,
    val count: Int,
)

/**
 * Pure, Gradle-free post-processor for Swift Export 2.4.0 generated output. Mirrors
 * [LocalizationGenerator]: the transforms are deterministic `String -> String` functions so they
 * are unit-testable; a thin file-walking wrapper ([patchPackage]) does the I/O.
 *
 * The native iOS app consumes the shared Kotlin core through Swift Export, whose Alpha codegen
 * emits Swift that doesn't compile and isn't idiomatic. Each transform here is keyed to Swift
 * Export 2.4.0's exact emitted shape and is removable when the corresponding upstream bug is fixed
 * (each `fun`'s doc explains which bug it patches). The transforms are behavior-preserving rewrites
 * — golden-fixture tests pin the output byte-for-byte — except that they return a match count so
 * the build can assert loudly when a tool bump shifts the generated shape and a regex matches zero.
 */
object SwiftExportSourcePatcher {
    /**
     * Swift reserved words. A generated case identifier (or sealed-enum case) that collides with
     * one must be back-tick-escaped. Shared by the sealed-enum and camelCase passes (it was
     * copy-pasted into both before the extraction).
     */
    internal val swiftKeywords: Set<String> =
        setOf(
            "default",
            "case",
            "where",
            "class",
            "enum",
            "protocol",
            "struct",
            "init",
            "deinit",
            "self",
            "super",
            "import",
            "return",
            "func",
            "var",
            "let",
            "if",
            "else",
            "for",
            "in",
            "do",
            "try",
            "catch",
            "throw",
            "true",
            "false",
            "nil",
            "is",
            "as",
            "guard",
            "switch",
            "public",
            "private",
            "internal",
            "static",
            "extension",
            "associatedtype",
            "operator",
            "repeat",
            "while",
            "break",
            "continue",
            "fallthrough",
            "defer",
            "subscript",
            "typealias",
        )

    private const val FLAT_TYPEALIAS_MARKER = "// --- swift-export flat typealias layer (generated) ---"
    private const val SEALED_ENUM_MARKER = "// --- swift-export sealed-enum support (generated) ---"
    private const val APP_RESULT_MARKER = "// --- swift-export AppResult accessor (generated) ---"

    /** The generated subtype-class names + erased base type for `AppResult`. See [appendAppResultAccessor]. */
    private const val APP_RESULT_SUCCESS_CLASS = "_ExportedKotlinPackages_com_calypsan_listenup_api_result_AppResult_Success"
    private const val APP_RESULT_FAILURE_CLASS = "_ExportedKotlinPackages_com_calypsan_listenup_api_result_AppResult_Failure"
    private const val APP_RESULT_BASE_TYPE = "ExportedKotlinPackages.com.calypsan.listenup.api.result.AppResult"

    /**
     * Operator-unavailability + undefined-type + `description`/`release()` collision fixes for one
     * generated `.swift` file. `count` is 1 if the file changed, else 0 (matching the per-file
     * "patched files" tally of the original transform).
     *
     * Two bug classes are patched, both behavior-preserving and removable upstream:
     *
     *   Class 1 — unavailable operator calling an unavailable helper. An operator that maps to a
     *   Swift `@available(*, unavailable)` declaration (e.g. `CoroutineDispatcher.+`, whose Kotlin
     *   `@Deprecated(level = ERROR)` becomes `unavailable`) has its body call an equally-unavailable
     *   `_helper`; Swift rejects calling an unavailable symbol even from inside another. These decls
     *   are uncallable from Swift, so rewriting only those bodies to `fatalError()` is safe. The
     *   transform is annotation-scoped + brace-aware — available operators (Timestamp, Duration,
     *   Comparable, …) that legitimately delegate to `this._helper` are left intact.
     *
     *   Class 2 — function referencing an undefined same-module type. Swift export references a
     *   nested sealed-interface type it never emits (e.g. `LocalDate.Companion.Format(block:)`'s
     *   closure parameter `DateTimeFormatBuilder.WithDate`). Such a function can't compile and isn't
     *   part of any API we call from Swift, so the whole declaration is deleted (brace-aware).
     *
     *   Class 3 — rename a generated `description` Kotlin property to `description_`. Swift export
     *   emits `public var description: <T>` for any Kotlin `description` field, which collides with
     *   the inherited `KotlinRuntime.KotlinBase.description: String` (different type -> an illegal
     *   override). SKIE renamed the same field to `description_`; matching that keeps existing Swift
     *   call sites stable.
     *
     *   Class 4 — an `@_spi` requirement stub duplicating its real implementation. Kotlin 2.4.20 maps
     *   an opt-in-annotated interface member to `@_spi(...)`, and for each one emits BOTH a
     *   `fatalError("'x' is an @_spi requirement …")` default for Swift conformers AND the bridged
     *   implementation, inside the same plain `extension P { }` — an invalid redeclaration
     *   (kotlinx-serialization's `CompositeDecoder.decodeSequentially` and two siblings). The stub is
     *   deleted only when a same-signature implementation sits in the same block; a stub with no twin
     *   (coroutines' `MutableSharedFlow.resetReplayCache`) is a legitimate default and stays.
     *
     *   Class 5 — a `<Name>_SealedType` enum case whose payload names a type the module never emits.
     *   Kotlin 2.4.20's native `sealedType()` enumerates every Kotlin subtype, including one nested
     *   in an `internal` class that Swift Export (correctly) does not export — kotlinx-datetime's
     *   `DateTimeFormatBuilder.WithDateTimeComponents` has exactly one subtype,
     *   `DateTimeComponentsFormat.Builder`, and the emitted case cannot resolve. The case and its
     *   `value` getter arm are deleted; the enum stays because its parent enum and `sealedType()`
     *   overloads still name it (the bridged `sealedType()` for it is a `fatalError` stub anyway).
     *
     * @param module the file's parent directory name (the Swift module), used to scope the
     *   undefined-type reference regex exactly as the original walk did.
     */
    fun patchSource(
        content: String,
        module: String,
    ): PatchOutcome {
        val unavailable = Regex("""@available\(\*, *unavailable""")
        val signatureClose = Regex("""\)\s*(->[^{]*)?\{\s*$""")
        val helperCall = Regex("""^(\s*)this\._[A-Za-z]+\(.*""")
        val typeDef = Regex("""\b(?:class|protocol|struct|enum|typealias|extension)\s+([A-Za-z0-9_]+)""")
        val funcStart = Regex("""^\s*(?:public|package|open|final|static|\s)*func\s""")

        val lines = content.lines().let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        val defined = lines.flatMap { line -> typeDef.findAll(line).map { it.groupValues[1] }.toList() }.toHashSet()
        val undefinedRef = Regex("""\Q$module\E\._ExportedKotlinPackages_([A-Za-z0-9_]+)""")
        val out = ArrayList<String>(lines.size)
        var changed = false
        var i = 0
        var armed = false
        var inBody = false
        var depth = 0
        while (i < lines.size) {
            val line = lines[i]

            // Class 2 — delete a whole function whose signature names an undefined same-module type.
            if (!inBody && funcStart.containsMatchIn(line)) {
                var j = i
                while (j < lines.size && !signatureClose.containsMatchIn(lines[j])) j++
                if (j < lines.size) {
                    val signature = lines.subList(i, j + 1).joinToString("\n")
                    val refsUndefined =
                        undefinedRef.findAll(signature).any { m ->
                            val name = m.groupValues[1]
                            "_ExportedKotlinPackages_$name" !in defined && name !in defined
                        }
                    if (refsUndefined) {
                        var d = lines[j].count { it == '{' } - lines[j].count { it == '}' }
                        var k = j + 1
                        while (k < lines.size && d > 0) {
                            d += lines[k].count { it == '{' } - lines[k].count { it == '}' }
                            k++
                        }
                        changed = true
                        i = k
                        continue
                    }
                }
            }

            // Class 1 — neutralize unavailable-operator bodies.
            when {
                !inBody && unavailable.containsMatchIn(line) -> {
                    armed = true
                    out.add(line)
                }

                armed && signatureClose.containsMatchIn(line) -> {
                    inBody = true
                    armed = false
                    depth = 1
                    out.add(line)
                }

                inBody -> {
                    val match = helperCall.matchEntire(line)
                    if (match != null) {
                        out.add("${match.groupValues[1]}fatalError(\"swift-export: unavailable operator\")")
                        changed = true
                    } else {
                        out.add(line)
                    }
                    depth += line.count { it == '{' } - line.count { it == '}' }
                    if (depth <= 0) inBody = false
                }

                else -> {
                    out.add(line)
                }
            }
            i++
        }

        // Class 3 — rename a generated `description` Kotlin property to `description_`. Guarded by
        // the property's own `_description_get` getter so framework `description`s are never touched.
        val descriptionProperty = Regex("""^(\s*)public var description: (.+)$""")
        for (idx in out.indices) {
            val match = descriptionProperty.matchEntire(out[idx]) ?: continue
            val getterNearby = (idx + 1..minOf(idx + 3, out.lastIndex)).any { out[it].contains("_description_get(") }
            if (getterNearby) {
                out[idx] = "${match.groupValues[1]}public var description_: ${match.groupValues[2]}"
                changed = true
            }
        }

        // Class 4 — drop an `@_spi` stub that duplicates a real implementation in its extension.
        val deduplicated = dropDuplicatedSpiStubs(out)
        if (deduplicated != null) {
            out.clear()
            out.addAll(deduplicated)
            changed = true
        }

        // Class 5 — drop a sealed-enum case whose payload the module never declares.
        val resolvable = dropUnexportedSealedCases(out, defined)
        if (resolvable != null) {
            out.clear()
            out.addAll(resolvable)
            changed = true
        }

        return if (changed) {
            PatchOutcome(out.joinToString("\n") + "\n", 1)
        } else {
            PatchOutcome(content, 0)
        }
    }

    private val spiStubBody =
        Regex("""^\s*fatalError\("'\w+' is an @_spi requirement that must be implemented by Swift conformers"\)\s*$""")
    private val spiAttribute = Regex("""^\s*@_spi\(""")
    private val funcDeclaration = Regex("""^\s*(?:public|package|open|final|static|\s)*func\s""")

    /** One function declaration inside a top-level block: where it sits and what it declares. */
    private data class SwiftFunction(
        val block: Int,
        val signature: String,
        val first: Int,
        val endExclusive: Int,
        val isSpiStub: Boolean,
    )

    /**
     * [patchSource] Class 4. Returns [lines] without every `@_spi` stub whose exact signature also has
     * a non-stub implementation in the same top-level block, or null when there is nothing to drop.
     * The stub's own `@_spi(...)` attribute line goes with it.
     */
    private fun dropDuplicatedSpiStubs(lines: List<String>): List<String>? {
        if (lines.none { spiStubBody.matches(it) }) return null
        val functions = ArrayList<SwiftFunction>()
        var depth = 0
        var block = -1
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (depth == 0 && line.contains('{')) block = i
            if (depth >= 1 && funcDeclaration.containsMatchIn(line)) {
                var j = i
                while (j < lines.size && !lines[j].contains('{')) j++
                if (j == lines.size) break
                val signature = lines.subList(i, j + 1).joinToString(" ").substringBefore('{').replace(Regex("""\s+"""), " ").trim()
                var bodyDepth = lines[j].count { it == '{' } - lines[j].count { it == '}' }
                var k = j + 1
                var isStub = false
                while (k < lines.size && bodyDepth > 0) {
                    if (spiStubBody.matches(lines[k])) isStub = true
                    bodyDepth += lines[k].count { it == '{' } - lines[k].count { it == '}' }
                    k++
                }
                val first = if (i > 0 && spiAttribute.containsMatchIn(lines[i - 1])) i - 1 else i
                functions.add(SwiftFunction(block, signature, first, k, isStub))
                i = k
                continue
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
            i++
        }
        val doomed =
            functions.filter { stub ->
                stub.isSpiStub &&
                    functions.any { !it.isSpiStub && it.block == stub.block && it.signature == stub.signature }
            }
        if (doomed.isEmpty()) return null
        val dropped = doomed.flatMapTo(HashSet()) { it.first until it.endExclusive }
        return lines.filterIndexed { index, _ -> index !in dropped }
    }

    private val sealedEnumStart = Regex("""^public enum (\w+_SealedType)\b""")
    private val sealedCasePayload =
        Regex("""^\s*case (\w+)\(ExportedKotlinPackages\.(?:[a-z]\w*\.)+([A-Z]\w*)\.\w+_SealedType\)\s*$""")

    /**
     * [patchSource] Class 5. Returns [lines] without every top-level `<Name>_SealedType` case whose
     * payload's outer type is not in [defined], and without that case's `value` getter arm inside the
     * same enum; null when every case resolves.
     */
    private fun dropUnexportedSealedCases(
        lines: List<String>,
        defined: Set<String>,
    ): List<String>? {
        val dropped = HashSet<Int>()
        var i = 0
        while (i < lines.size) {
            if (!sealedEnumStart.containsMatchIn(lines[i])) {
                i++
                continue
            }
            var depth = lines[i].count { it == '{' } - lines[i].count { it == '}' }
            var end = i + 1
            while (end < lines.size && depth > 0) {
                depth += lines[end].count { it == '{' } - lines[end].count { it == '}' }
                end++
            }
            val doomedCases = HashSet<String>()
            for (index in i + 1 until end) {
                val case = sealedCasePayload.matchEntire(lines[index]) ?: continue
                if (case.groupValues[2] !in defined) {
                    doomedCases.add(case.groupValues[1])
                    dropped.add(index)
                }
            }
            for (case in doomedCases) {
                val arm = Regex("""^\s*case let \.\Q$case\E\(type\): type\.value\s*$""")
                for (index in i + 1 until end) if (arm.matches(lines[index])) dropped.add(index)
            }
            i = end
        }
        if (dropped.isEmpty()) return null
        return lines.filterIndexed { index, _ -> index !in dropped }
    }

    /**
     * Flat top-level typealiases for nested `ExportedKotlinPackages` types, appended onto the
     * generated `Shared.swift`. `flattenPackage` does NOT actually flatten in Kotlin 2.4.0 — every
     * exported type is nested as `ExportedKotlinPackages.com.calypsan.listenup.<pkg>.<Type>`, so
     * `import Shared; Book` can't resolve. SKIE gave callers flat names; to match that, append a
     * top-level `public typealias` for every exported type. Idempotent via a marker. Name
     * collisions across packages resolve to the `client.domain.model` (then any `client.domain`)
     * variant; remaining ambiguous names are skipped and stay qualified. Underscore-prefixed names
 * (Swift Export's `__<Name>` sealed marker protocols, public since Kotlin 2.4.20) are skipped too.
     *
     * @param sharedContent the `Shared.swift` contents the aliases are appended to.
     * @param sourceContents the `Shared.swift` + `ListenupContract.swift` contents to harvest types
     *   from (both modules' types are in scope on `Shared.swift`).
     * @return the rewritten `Shared.swift` and the number of aliases emitted.
     */
    fun appendFlatTypealiases(
        sharedContent: String,
        sourceContents: List<String>,
    ): PatchOutcome {
        if (sharedContent.contains(FLAT_TYPEALIAS_MARKER)) return PatchOutcome(sharedContent, 0)
        val extensionRe = Regex("""^extension ExportedKotlinPackages\.([A-Za-z0-9_.]+?)(?:\s+where\b.*)?\s*\{""")
        val typeRe = Regex("""^\s+public (?:final class|class|enum|protocol|struct|actor) ([A-Za-z_][A-Za-z0-9_]*)""")
        val packagesByName = HashMap<String, MutableSet<String>>()
        for (fileContent in sourceContents) {
            var packageNamespace: String? = null
            var depth = 0
            for (line in fileContent.lineSequence()) {
                if (packageNamespace == null) {
                    val ext = extensionRe.find(line)
                    if (ext != null) {
                        val path = ext.groupValues[1]
                        // A package extension's last segment is lowercase; a type/conformance extension's is Capitalized.
                        if (path.substringAfterLast('.').first().isLowerCase()) {
                            packageNamespace = path
                            depth = line.count { it == '{' } - line.count { it == '}' }
                        }
                    }
                    continue
                }
                // Only alias types declared DIRECTLY in the package (depth 1) — not sealed subtypes or
                // other types nested inside a parent type (depth > 1), whose flat path would be wrong.
                if (depth == 1) {
                    typeRe
                        .find(
                            line,
                        )?.let { packagesByName.getOrPut(it.groupValues[1]) { HashSet() }.add(packageNamespace!!) }
                }
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (depth <= 0) packageNamespace = null
            }
        }
        val builder = StringBuilder("\n$FLAT_TYPEALIAS_MARKER\n")
        var count = 0
        for ((name, namespaces) in packagesByName.toSortedMap()) {
            // `Companion` is every class's nested object; a leading underscore marks generator
            // plumbing (Kotlin 2.4.20's `public protocol __<Name>` sealed markers). Neither is API.
            if (name == "Companion" || name.startsWith("_")) continue
            val namespace =
                when {
                    namespaces.size == 1 -> {
                        namespaces.first()
                    }

                    else -> {
                        namespaces.firstOrNull { it.contains(".client.domain.model") }
                            ?: namespaces.firstOrNull { it.contains(".client.domain") }
                            ?: continue
                    }
                }
            builder.append("public typealias $name = ExportedKotlinPackages.$namespace.$name\n")
            count++
        }
        return PatchOutcome(sharedContent + builder.toString(), count)
    }

    /** A sealed parent type harvested from the generated Swift: its package [path] and [name]. */
    internal data class SealedParent(
        val path: String,
        val name: String,
    )

    // A sealed subtype: `public final class <Class>: KotlinRuntime.KotlinBase,
    // ExportedKotlinPackages.<path>.<Parent>, ExportedKotlinPackages.<path>._<Parent> {`
    private val subtypeRe =
        Regex(
            """^public final class (_ExportedKotlinPackages_\w+): KotlinRuntime\.KotlinBase, ExportedKotlinPackages\.([\w.]+)\.(\w+), ExportedKotlinPackages\.[\w.]+\.__\3\b""",
        )

    /**
     * Harvest every sealed parent and its subtypes from the generated Swift, keyed by the same
     * [SealedParent] used to emit the `onEnum` support. Single source of truth shared by
     * [appendSealedEnumSupport] (the emitter) and [sealedSubtypeDrift] (the exact-count guard), so
     * the count the build asserts against is exactly the count the generator would emit.
     */
    internal fun harvestSealedSubtypes(sourceContents: List<String>): LinkedHashMap<SealedParent, MutableList<Pair<String, String>>> {
        val sealedTypes = LinkedHashMap<SealedParent, MutableList<Pair<String, String>>>()
        for (fileContent in sourceContents) {
            for (line in fileContent.lineSequence()) {
                val m = subtypeRe.find(line) ?: continue
                val (className, path, parent) = m.destructured
                val subtype = className.substringAfterLast("_${parent}_")
                if (subtype.isBlank() || subtype == className) continue
                sealedTypes.getOrPut(SealedParent(path, parent)) { mutableListOf() }.add(subtype to className)
            }
        }
        return sealedTypes
    }


    /**
     * Compares the harvested subtypes of each sealed parent against what the Kotlin sources
     * actually declare ([SealedHierarchyScanner]), returning one human-readable line per drift.
     *
     * This replaced a map of 127 hand-typed counts. That map was only a *shrink floor* — a parent
     * that legitimately grew was deliberately not flagged — so its numbers rotted silently:
     * `PlaybackUpdate` sat at 10 against a real 13, and could have lost two subtypes while still
     * clearing the floor meant to protect it. Source is the one expectation that cannot go stale.
     *
     * Only parents the harvest actually found are checked. Source declares roughly twice the sealed
     * types Swift Export emits (the export surface is deliberately lean), so asserting that every
     * source subtype is emitted would fail on ~300 legitimate absences. Validated against a real
     * 2.4.10 `Shared.swift`: 128 exported parents, 0 mismatches.
     *
     * @param sourceContents the generated `Shared.swift` + `ListenupContract.swift` contents.
     * @param declaredInSource parent simple name -> concrete subtype simple names, from Kotlin source.
     */
    internal fun sealedSubtypeDrift(
        sourceContents: List<String>,
        declaredInSource: Map<String, Set<String>>,
    ): List<String> =
        harvestSealedSubtypes(sourceContents).mapNotNull { (parent, subtypes) ->
            val qualified = "${parent.path}.${parent.name}"
            val expected =
                declaredInSource[parent.name]
                    ?: return@mapNotNull "$qualified: harvested a sealed type that the Kotlin sources do not " +
                        "declare. Either the scanner's source roots no longer cover this type, or the emitted " +
                        "shape changed — until they agree the exhaustive-switch guarantee is unverifiable."
            val harvested = subtypes.map { (subtype, _) -> subtype }.toSet()
            val missing = expected - harvested
            if (missing.isEmpty()) {
                null
            } else {
                "$qualified: Kotlin declares ${expected.size} concrete subtype(s) but ${harvested.size} " +
                    "were harvested — missing ${missing.sorted().joinToString(", ")}. Each dropped subtype " +
                    "would fall to the generated `unknown` case instead of getting its own Swift case."
            }
        }

    /**
     * Sealed-class enum support (the `onEnum(of:)` exhaustive-switch helper), appended onto the
     * generated `Shared.swift`. Swift export maps a Kotlin sealed class to `protocol <Name>` +
     * marker `package protocol __<Name>` (Kotlin 2.4.20 renamed it from `_<Name>`; the
     * exact-count guard caught the rename as an all-parents-harvested-zero drift) + one
     * `public final class _ExportedKotlinPackages_<path>_<Name>_<Subtype>` per subtype (each
     * conforming to both). That gives no exhaustive Swift `switch`. SKIE gave callers `onEnum(of:)`
     * returning a Swift enum; this regenerates that. Per sealed type, appended onto Shared.swift:
     *   • a flat alias `<Name><Subtype>` for each subtype (SKIE's nested-subtype name), and
     *   • `enum` (case = lowercased subtype, associated value = the subtype) + a generated `unknown`
     *     case carrying the base type + an `onEnum(of: <Name>)` overload that `as?`-casts to each
     *     subtype and returns `.unknown(value)` for anything that matches none. Idempotent via a
     *     marker.
     *
     * @param sharedContent the `Shared.swift` contents the support is appended to.
     * @param sourceContents the `Shared.swift` + `ListenupContract.swift` contents to harvest
     *   subtypes from.
     * @return the rewritten `Shared.swift` and `count` = number of sealed parents emitted.
     */
    fun appendSealedEnumSupport(
        sharedContent: String,
        sourceContents: List<String>,
    ): PatchOutcome {
        if (sharedContent.contains(SEALED_ENUM_MARKER)) return PatchOutcome(sharedContent, 0)
        val sealedTypes = harvestSealedSubtypes(sourceContents)
        if (sealedTypes.isEmpty()) return PatchOutcome(sharedContent, 0)
        val builder = StringBuilder("\n$SEALED_ENUM_MARKER\n")
        val emittedAlias = HashSet<String>()
        var count = 0
        for ((parent, subtypes) in sealedTypes) {
            val enumName = "OnEnum_${parent.path.replace('.', '_')}_${parent.name}"
            // The synthetic catch-all case name. Normally `unknown`, but a real Kotlin subtype named
            // `Unknown` already maps to `case unknown` (e.g. `Reachability.Unknown`); emitting another
            // `unknown` is an invalid Swift redeclaration. So pick the first name that doesn't collide
            // with any real subtype's lowercased-first-char case — keeps `.unknown` for the 99% case,
            // sidesteps the collision for the rare real-`Unknown` enum without consumer churn.
            val existingCases = subtypes.mapTo(HashSet()) { (subtype, _) -> subtype.replaceFirstChar { it.lowercase() } }
            val catchAll = generateSequence(0) { it + 1 }
                .map { if (it == 0) "unknown" else "unknownCatchAll$it" }
                .first { it !in existingCases }
            // subtype flat aliases (SKIE's `<Parent><Subtype>` name)
            for ((subtype, className) in subtypes) {
                val alias = "${parent.name}$subtype"
                if (emittedAlias.add(alias)) builder.append("public typealias $alias = $className\n")
            }
            // enum — one case per subtype, plus a synthetic catch-all carrying the base type so a value
            // matching no known subtype degrades gracefully (the consumer `switch` is forced to handle
            // it) instead of hitting a runtime `fatalError`. See Plan 004.
            builder.append("public enum $enumName {\n")
            for ((subtype, className) in subtypes) {
                val raw = subtype.replaceFirstChar { it.lowercase() }
                val case = if (raw in swiftKeywords) "`$raw`" else raw
                builder.append("    case $case($className)\n")
            }
            builder.append("    case $catchAll(ExportedKotlinPackages.${parent.path}.${parent.name})\n")
            builder.append("}\n")
            // onEnum overload
            builder.append(
                "public func onEnum(of value: ExportedKotlinPackages.${parent.path}.${parent.name}) -> $enumName {\n",
            )
            for ((subtype, className) in subtypes) {
                val raw = subtype.replaceFirstChar { it.lowercase() }
                val case = if (raw in swiftKeywords) "`$raw`" else raw
                builder.append("    if let value = value as? $className { return .$case(value) }\n")
            }
            builder.append("    return .$catchAll(value)\n}\n")
            count++
        }
        return PatchOutcome(sharedContent + builder.toString(), count)
    }

    /**
     * Typed `AppResult` accessor, appended onto the generated `Shared.swift`. `AppResult<T>` is the
     * success/failure envelope every fallible repository call returns — but because it's *generic*,
     * Swift Export erases it to `any AppResult` and its subtypes are emitted as plain
     * `_ExportedKotlinPackages_…_AppResult_Success` / `…_Failure` classes that do *not* conform to the
     * `AppResult` protocol, so [appendSealedEnumSupport]'s [subtypeRe] never matches them and no
     * `onEnum` is generated. Consumers were left hand-casting a 60-char mangled symbol — and the two
     * sites diverged (one folded the failure, one silently dropped it).
     *
     * This emits a clean, compiler-forced accessor (idempotent, behind a marker): flat typealiases for
     * the two subtype classes, an `AppResultCase` enum (`success` / `failure` / a defensive `unknown`
     * tail — never a silent success), and an `appResultCase(_:)` fold over the erased base type. The
     * success payload stays accessible (`success.data as? T`) via the clean `AppResultSuccess` alias.
     *
     * Generated only when the `_AppResult_Success`/`_Failure` classes are actually present in
     * [sourceContents] (scoped to `AppResult` — the one generic sealed type that matters; a general
     * generic-sealed solution is out of scope). Removable when Swift Export gains generic-sealed
     * support upstream (Plan 013).
     *
     * @param sharedContent the `Shared.swift` contents the accessor is appended to.
     * @param sourceContents the `Shared.swift` + `ListenupContract.swift` contents to detect the
     *   `AppResult` subtype classes in (they live in the `:contract` module's `ListenupContract.swift`).
     * @return the rewritten `Shared.swift` and `count` = 1 if the accessor was emitted, else 0.
     */
    fun appendAppResultAccessor(
        sharedContent: String,
        sourceContents: List<String>,
    ): PatchOutcome {
        if (sharedContent.contains(APP_RESULT_MARKER)) return PatchOutcome(sharedContent, 0)
        val hasSuccess = sourceContents.any { it.contains("public final class $APP_RESULT_SUCCESS_CLASS:") }
        val hasFailure = sourceContents.any { it.contains("public final class $APP_RESULT_FAILURE_CLASS:") }
        if (!hasSuccess || !hasFailure) return PatchOutcome(sharedContent, 0)

        val block =
            """

            $APP_RESULT_MARKER
            public typealias AppResultSuccess = $APP_RESULT_SUCCESS_CLASS
            public typealias AppResultFailure = $APP_RESULT_FAILURE_CLASS
            public enum AppResultCase {
                case success(AppResultSuccess)
                case failure(AppResultFailure)
                case unknown(any $APP_RESULT_BASE_TYPE)
            }
            public func appResultCase(_ value: any $APP_RESULT_BASE_TYPE) -> AppResultCase {
                if let failure = value as? AppResultFailure { return .failure(failure) }
                if let success = value as? AppResultSuccess { return .success(success) }
                return .unknown(value)
            }
            """.trimIndent() + "\n"
        return PatchOutcome(sharedContent + block, 1)
    }

    /**
     * `SCREAMING_SNAKE` generated enum cases -> camelCase for one generated `.swift` file. Swift
     * export emits Kotlin enum entries with their raw `SCREAMING_SNAKE` names (`.SYSTEM`,
     * `.FIRST_NAME`), which is jarring in Swift. Rewrite the generated case identifiers to camelCase
     * (`.system`, `.firstName`) so call sites read idiomatically. Only the `case X` declarations and
     * `.X` case references inside the enum are touched — the `"X"` string literals in the generated
     * `description`/`init?(_:)` (which round-trip the Kotlin name on the wire) and the `com_…_X()` C
     * bridge functions are left exactly as-is, so behavior is identical. Scoped per enum: only `.X`
     * where `X` is a declared case of that same enum is rewritten. Idempotent (already-camelCased
     * cases don't match the `SCREAMING_SNAKE` shape).
     *
     * @return the rewritten file and `count` = 1 if the file changed, else 0.
     */
    fun camelCaseEnumCases(content: String): PatchOutcome {
        val enumStart = Regex("""^(\s*)public enum (\w+):""")
        val caseDecl = Regex("""^(\s*)case ([A-Z][A-Z0-9_]+)\s*$""")

        fun toCamel(name: String): String {
            val camel =
                name
                    .split("_")
                    .mapIndexed { i, part ->
                        if (i == 0) part.lowercase() else part.lowercase().replaceFirstChar { it.uppercase() }
                    }.joinToString("")
            return if (camel in swiftKeywords) "`$camel`" else camel
        }

        val lines = content.lines().let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        val out = ArrayList<String>(lines.size)
        var changed = false
        var inEnum = false
        var enumDepth = 0
        var depth = 0
        val caseMap = HashMap<String, String>()
        for (line in lines) {
            if (!inEnum) {
                if (enumStart.containsMatchIn(line)) {
                    inEnum = true
                    enumDepth = depth
                    caseMap.clear()
                    depth += line.count { it == '{' } - line.count { it == '}' }
                    out.add(line)
                    continue
                }
                out.add(line)
                depth += line.count { it == '{' } - line.count { it == '}' }
                continue
            }
            // Inside an enum body.
            var rewritten = line
            val decl = caseDecl.matchEntire(line)
            if (decl != null) {
                val camel = toCamel(decl.groupValues[2])
                if (camel != decl.groupValues[2]) {
                    caseMap[decl.groupValues[2]] = camel
                    rewritten = "${decl.groupValues[1]}case $camel"
                    changed = true
                }
            } else if (caseMap.isNotEmpty()) {
                for ((raw, camel) in caseMap) {
                    if (rewritten.contains(".$raw")) {
                        rewritten = rewritten.replace(Regex("""\.$raw\b"""), ".$camel")
                        changed = true
                    }
                }
            }
            out.add(rewritten)
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= enumDepth) inEnum = false
        }
        return if (changed) {
            PatchOutcome(out.joinToString("\n") + "\n", 1)
        } else {
            PatchOutcome(content, 0)
        }
    }

    /**
     * Walks a generated SPM package dir, applies every transform to the right files, returns
     * per-pass counts keyed `patchSource` / `camelCase` / `flatTypealias` / `sealedEnum`. The only
     * File-touching code; mirrors [LocalizationArtifacts]'s role around [LocalizationGenerator].
     *
     * Order matches the original `doLast`: per-file passes first (`patchSource`, `camelCase`), then
     * the Shared.swift-append passes (`flatTypealias`, `sealedEnum`, `appResult`). A missing root
     * yields all-zero counts (the original returned 0 from each transform).
     *
     * Before emitting the sealed-enum support, it enforces the exact-count guard
     * ([sealedSubtypeDrift]): a *partial* subtype drop on a known sealed type fails the build loudly
     * here (naming the parent), rather than slipping the dropped subtype to the `unknown` case
     * silently. The aggregate `> 0` floor in `build.gradle.kts` stays as the *total*-failure net.
     */
    fun patchPackage(
        root: File,
        kotlinSourceRoots: List<File>,
    ): Map<String, Int> {
        if (!root.exists()) {
            return mapOf(
                "patchSource" to 0,
                "camelCase" to 0,
                "flatTypealias" to 0,
                "sealedEnum" to 0,
                "appResult" to 0,
            )
        }

        var patchSourceCount = 0
        root.walkTopDown().filter { it.isFile && it.extension == "swift" }.forEach { file ->
            val outcome = patchSource(file.readText(), file.parentFile.name)
            if (outcome.count > 0) {
                file.writeText(outcome.content)
                patchSourceCount++
            }
        }

        var camelCaseCount = 0
        for (file in moduleSourceFiles(root)) {
            val outcome = camelCaseEnumCases(file.readText())
            if (outcome.count > 0) {
                file.writeText(outcome.content)
                camelCaseCount++
            }
        }

        val sharedFile = sharedSwiftFile(root)
        val sourceContents = moduleSourceFiles(root).map { it.readText() }

        val flatTypealiasCount: Int
        if (sharedFile != null) {
            val outcome = appendFlatTypealiases(sharedFile.readText(), sourceContents)
            if (outcome.content != sharedFile.readText()) sharedFile.writeText(outcome.content)
            flatTypealiasCount = outcome.count
        } else {
            flatTypealiasCount = 0
        }

        val sealedEnumCount: Int
        if (sharedFile != null) {
            // Re-read source contents: the flat-typealias pass mutated Shared.swift above.
            val sealedSources = moduleSourceFiles(root).map { it.readText() }
            val drift = sealedSubtypeDrift(sealedSources, SealedHierarchyScanner.scanSourceRoots(kotlinSourceRoots))
            check(drift.isEmpty()) {
                "Swift Export patcher: the generated Swift and the Kotlin sources disagree about a sealed " +
                    "hierarchy. A subtype Kotlin declares was not harvested, so it would fall to the generated " +
                    "`unknown` case instead of getting its own Swift case. This is read from source — there is " +
                    "no count to update; fix the harvest or the emitted shape.\n  - " + drift.joinToString("\n  - ")
            }
            val outcome = appendSealedEnumSupport(sharedFile.readText(), sealedSources)
            if (outcome.content != sharedFile.readText()) sharedFile.writeText(outcome.content)
            sealedEnumCount = outcome.count
        } else {
            sealedEnumCount = 0
        }

        val appResultCount: Int
        if (sharedFile != null) {
            val appResultSources = moduleSourceFiles(root).map { it.readText() }
            val outcome = appendAppResultAccessor(sharedFile.readText(), appResultSources)
            if (outcome.content != sharedFile.readText()) sharedFile.writeText(outcome.content)
            appResultCount = outcome.count
        } else {
            appResultCount = 0
        }

        return mapOf(
            "patchSource" to patchSourceCount,
            "camelCase" to camelCaseCount,
            "flatTypealias" to flatTypealiasCount,
            "sealedEnum" to sealedEnumCount,
            "appResult" to appResultCount,
        )
    }

    /** The generated `Shared/Shared.swift`, or null if the package hasn't generated it. */
    private fun sharedSwiftFile(root: File): File? =
        root.walkTopDown().firstOrNull { it.name == "Shared.swift" && it.parentFile.name == "Shared" }

    /** The `Shared.swift` + `ListenupContract.swift` module roots the harvest passes read. */
    private fun moduleSourceFiles(root: File): List<File> =
        listOf("Shared", "ListenupContract").mapNotNull { module ->
            root.walkTopDown().firstOrNull { it.name == "$module.swift" && it.parentFile.name == module }
        }
}
