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
     * Swift reserved words. A generated case identifier that collides with one must be
     * back-tick-escaped by the camelCase pass.
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
    private const val SEALED_ALIAS_MARKER = "// --- swift-export sealed subtype aliases (generated) ---"

    /**
     * Operator-unavailability + undefined-type + codegen-shape fixes for one
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
     *   Class 6 — a `<Name>_SealedType` enum whose `value` type is a protocol its payload classes do
     *   not conform to. Generics are erased: `AppResult<T>` becomes `protocol AppResult`, while its
     *   subtypes are emitted as plain `KotlinRuntime.KotlinBase` classes that never adopt it, so the
     *   generated getter cannot return them as `AppResult`. The getter's type is widened to
     *   `KotlinRuntime.KotlinBase`, the one supertype every payload has. Only `AppResult` has this
     *   shape today (scanned across every generated module); a conformed protocol is left alone.
     *
     * @param module the file's parent directory name (the Swift module), used to scope the
     *   undefined-type reference regex exactly as the original walk did.
     */
    fun patchSource(
        content: String,
        module: String,
    ): PatchOutcome {
        val lines = linesOf(content)
        val defined = lines.flatMap { line -> typeDef.findAll(line).map { it.groupValues[1] }.toList() }.toHashSet()
        val passes: List<(List<String>) -> List<String>?> =
            listOf(
                { deleteUndefinedTypeFunctions(it, module, defined) }, // Class 2
                ::neutralizeUnavailableOperators, // Class 1
                ::dropDuplicatedSpiStubs, // Class 4
                { dropUnexportedSealedCases(it, defined) }, // Class 5
                ::widenUnconformedSealedValues, // Class 6
            )
        var out = lines
        var changed = false
        for (pass in passes) {
            val next = pass(out) ?: continue
            out = next
            changed = true
        }
        return if (changed) PatchOutcome(out.joinToString("\n") + "\n", 1) else PatchOutcome(content, 0)
    }

    private val unavailable = Regex("""@available\(\*, *unavailable""")
    private val signatureClose = Regex("""\)\s*(->[^{]*)?\{\s*$""")
    private val helperCall = Regex("""^(\s*)this\._[A-Za-z]+\(.*""")
    private val typeDef = Regex("""\b(?:class|protocol|struct|enum|typealias|extension)\s+([A-Za-z0-9_]+)""")

    /** Lines of [content] without the empty element a trailing newline would leave. */
    private fun linesOf(content: String): List<String> =
        content.lines().let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }

    /** `{` minus `}` on one line — the brace arithmetic every block walk below is built on. */
    private fun braceDelta(line: String): Int = line.count { it == '{' } - line.count { it == '}' }

    /** Exclusive end of the block whose opening brace sits on line [open]: the first line after the depth returns to zero. */
    private fun blockEnd(
        lines: List<String>,
        open: Int,
    ): Int {
        var depth = braceDelta(lines[open])
        var end = open + 1
        while (end < lines.size && depth > 0) {
            depth += braceDelta(lines[end])
            end++
        }
        return end
    }

    /** Index of the line that closes the signature opened on line [from], or -1 when none does. */
    private fun signatureCloseIndex(
        lines: List<String>,
        from: Int,
    ): Int {
        var j = from
        while (j < lines.size && !signatureClose.containsMatchIn(lines[j])) j++
        return if (j < lines.size) j else -1
    }

    /**
     * [patchSource] Class 2. Returns [lines] without every function whose signature names a
     * same-module `_ExportedKotlinPackages_…` type the file never declares, or null when none does.
     */
    private fun deleteUndefinedTypeFunctions(
        lines: List<String>,
        module: String,
        defined: Set<String>,
    ): List<String>? {
        val undefinedRef = Regex("""\Q$module\E\._ExportedKotlinPackages_([A-Za-z0-9_]+)""")
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val close = if (funcDeclaration.containsMatchIn(lines[i])) signatureCloseIndex(lines, i) else -1
            if (close >= 0 &&
                refersToUndefinedType(lines.subList(i, close + 1).joinToString("\n"), undefinedRef, defined)
            ) {
                i = blockEnd(lines, close)
                continue
            }
            out.add(lines[i])
            i++
        }
        return if (out.size != lines.size) out else null
    }

    private fun refersToUndefinedType(
        signature: String,
        undefinedRef: Regex,
        defined: Set<String>,
    ): Boolean =
        undefinedRef.findAll(signature).any { m ->
            val name = m.groupValues[1]
            "_ExportedKotlinPackages_$name" !in defined && name !in defined
        }

    /**
     * [patchSource] Class 1. Inside an `@available(*, unavailable)` operator, the `this._helper(...)`
     * call becomes `fatalError`; returns the rewritten lines, or null when nothing was rewritten.
     */
    private fun neutralizeUnavailableOperators(lines: List<String>): List<String>? {
        val out = ArrayList<String>(lines.size)
        var changed = false
        var armed = false
        var inBody = false
        var depth = 0
        for (line in lines) {
            var emitted = line
            when {
                !inBody && unavailable.containsMatchIn(line) -> {
                    armed = true
                }

                armed && signatureClose.containsMatchIn(line) -> {
                    inBody = true
                    armed = false
                    depth = 1
                }

                inBody -> {
                    helperCall.matchEntire(line)?.let {
                        emitted = "${it.groupValues[1]}fatalError(\"swift-export: unavailable operator\")"
                        changed = true
                    }
                    depth += braceDelta(line)
                    if (depth <= 0) inBody = false
                }
            }
            out.add(emitted)
        }
        return if (changed) out else null
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
        val functions = swiftFunctions(lines)
        val doomed =
            functions.filter { stub ->
                stub.isSpiStub &&
                    functions.any { !it.isSpiStub && it.block == stub.block && it.signature == stub.signature }
            }
        if (doomed.isEmpty()) return null
        val dropped = doomed.flatMapTo(HashSet()) { it.first until it.endExclusive }
        return lines.filterIndexed { index, _ -> index !in dropped }
    }

    /** Every function declared inside a top-level block, with its normalized signature and body span. */
    private fun swiftFunctions(lines: List<String>): List<SwiftFunction> {
        val functions = ArrayList<SwiftFunction>()
        var depth = 0
        var block = -1
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (depth == 0 && line.contains('{')) block = i
            val open = if (depth >= 1 && funcDeclaration.containsMatchIn(line)) openingBraceIndex(lines, i) else -1
            if (open < 0) {
                depth += braceDelta(line)
                i++
                continue
            }
            val end = blockEnd(lines, open)
            val isStub = (open + 1 until end).any { spiStubBody.matches(lines[it]) }
            val first = if (i > 0 && spiAttribute.containsMatchIn(lines[i - 1])) i - 1 else i
            functions.add(SwiftFunction(block, normalizedSignature(lines, i, open), first, end, isStub))
            i = end
        }
        return functions
    }

    /** Index of the first line at or after [from] carrying a `{`, or -1. */
    private fun openingBraceIndex(
        lines: List<String>,
        from: Int,
    ): Int {
        var j = from
        while (j < lines.size && !lines[j].contains('{')) j++
        return if (j < lines.size) j else -1
    }

    /** The declaration on lines [from]..[open], up to its `{`, with whitespace collapsed. */
    private fun normalizedSignature(
        lines: List<String>,
        from: Int,
        open: Int,
    ): String =
        lines
            .subList(from, open + 1)
            .joinToString(" ")
            .substringBefore('{')
            .replace(Regex("""\s+"""), " ")
            .trim()

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

    private val anySealedEnumStart = Regex("""^\s*public enum (\w+_SealedType): KotlinRuntimeSupport\.SealedType\b""")
    private val sealedCase = Regex("""^\s*case \w+\((\S+?)\)\s*$""")
    private val sealedValueGetter = Regex("""^(\s*)public var value: (\S+) \{\s*$""")
    private val protocolDeclaration = Regex("""\bprotocol (\w+)\b""")
    private val classDeclaration = Regex("""\bclass (\w+): ([^{]*)\{""")
    private val wrapperStruct = Regex("""\bstruct (\w+_SealedType): KotlinRuntimeSupport\.SealedType\b""")
    private val wrapperValue = Regex("""^\s*public let value: (\S+)\s*$""")

    /**
     * [patchSource] Class 6. Rewrites, in place, the `value` getter type of every `*_SealedType` enum
     * whose declared type is a protocol of this module that at least one payload's wrapped class does
     * not conform to; returns the rewritten lines, or null when every payload conforms.
     */
    private fun widenUnconformedSealedValues(lines: List<String>): List<String>? {
        val out = ArrayList(lines)
        val protocols =
            lines
                .flatMap { line ->
                    protocolDeclaration.findAll(line).map { it.groupValues[1] }.toList()
                }.toHashSet()
        val conformances = HashMap<String, String>()
        val wrappedClass = HashMap<String, String>()
        lines.forEachIndexed { index, line ->
            classDeclaration.find(line)?.let { conformances[it.groupValues[1]] = it.groupValues[2] }
            wrapperStruct.find(line)?.let { struct ->
                lines.getOrNull(index + 1)?.let { next ->
                    wrapperValue.matchEntire(next)?.let {
                        wrappedClass[struct.groupValues[1]] =
                            it.groupValues[1].substringAfterLast('.')
                    }
                }
            }
        }
        var changed = false
        var i = 0
        while (i < lines.size) {
            if (!anySealedEnumStart.containsMatchIn(lines[i])) {
                i++
                continue
            }
            var depth = lines[i].count { it == '{' } - lines[i].count { it == '}' }
            var end = i + 1
            while (end < lines.size && depth > 0) {
                depth += lines[end].count { it == '{' } - lines[end].count { it == '}' }
                end++
            }
            val body = i + 1 until end
            val getter = body.firstOrNull { sealedValueGetter.matches(lines[it]) }
            if (getter != null) {
                val match = sealedValueGetter.matchEntire(lines[getter])!!
                val valueType = match.groupValues[2].substringAfterLast('.')
                val payloads =
                    body.mapNotNull {
                        sealedCase
                            .matchEntire(
                                lines[it],
                            )?.groupValues
                            ?.get(1)
                            ?.substringAfterLast('.')
                    }
                val unconformed =
                    valueType in protocols &&
                        payloads.any { payload ->
                            val cls = wrappedClass[payload] ?: return@any false
                            val supertypes = conformances[cls] ?: return@any false
                            !Regex("""\b${Regex.escape(valueType)}\b""").containsMatchIn(supertypes)
                        }
                if (unconformed) {
                    out[getter] = "${match.groupValues[1]}public var value: KotlinRuntime.KotlinBase {"
                    changed = true
                }
            }
            i = end
        }
        return if (changed) out else null
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
        val builder = StringBuilder("\n$FLAT_TYPEALIAS_MARKER\n")
        var count = 0
        for ((name, namespaces) in packageLevelTypes(sourceContents).toSortedMap()) {
            // `Companion` is every class's nested object; a leading underscore marks generator
            // plumbing (Kotlin 2.4.20's `public protocol __<Name>` sealed markers). Neither is API.
            if (name == "Companion" || name.startsWith("_")) continue
            val namespace = preferredNamespace(namespaces) ?: continue
            builder.append("public typealias $name = ExportedKotlinPackages.$namespace.$name\n")
            count++
        }
        return PatchOutcome(sharedContent + builder.toString(), count)
    }

    private val extensionRe = Regex("""^extension ExportedKotlinPackages\.([A-Za-z0-9_.]+?)(?:\s+where\b.*)?\s*\{""")
    private val typeRe =
        Regex("""^\s+public (?:final class|class|enum|protocol|struct|actor) ([A-Za-z_][A-Za-z0-9_]*)""")

    /**
     * A name declared in one package resolves to it. One declared in several resolves to the
     * `client.domain.model` package, then any `client.domain` one; anything still ambiguous is null
     * and stays qualified.
     */
    private fun preferredNamespace(namespaces: Set<String>): String? =
        if (namespaces.size == 1) {
            namespaces.first()
        } else {
            namespaces.firstOrNull { it.contains(".client.domain.model") }
                ?: namespaces.firstOrNull { it.contains(".client.domain") }
        }

    /**
     * Type name -> every package it is declared DIRECTLY in. Only depth 1 of a package extension
     * counts — a sealed subtype or any other type nested inside a parent type (depth > 1) would get
     * a flat path that is wrong.
     */
    private fun packageLevelTypes(sourceContents: List<String>): Map<String, Set<String>> {
        val packagesByName = HashMap<String, MutableSet<String>>()
        for (fileContent in sourceContents) {
            var packageNamespace: String? = null
            var depth = 0
            for (line in fileContent.lineSequence()) {
                val namespace = packageNamespace
                if (namespace == null) {
                    packageNamespace = packageExtension(line)?.also { depth = braceDelta(line) }
                    continue
                }
                if (depth == 1) {
                    typeRe.find(line)?.let { packagesByName.getOrPut(it.groupValues[1]) { HashSet() }.add(namespace) }
                }
                depth += braceDelta(line)
                if (depth <= 0) packageNamespace = null
            }
        }
        return packagesByName
    }

    /**
     * The package path an `extension ExportedKotlinPackages.<path> {` line opens, or null for any
     * other line — including a type/conformance extension, whose last segment is Capitalized where a
     * package's is lowercase.
     */
    private fun packageExtension(line: String): String? {
        val path = extensionRe.find(line)?.groupValues?.get(1) ?: return null
        return path.takeIf { it.substringAfterLast('.').first().isLowerCase() }
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
            """^public final class (_ExportedKotlinPackages_\w+): KotlinRuntime\.KotlinBase, """ +
                """ExportedKotlinPackages\.([\w.]+)\.(\w+), ExportedKotlinPackages\.[\w.]+\.__\3\b""",
        )

    /**
     * Harvest every sealed parent and its subtypes from the generated Swift, keyed by
     * [SealedParent]. [appendSealedSubtypeAliases] emits one flat alias per harvested subtype.
     */
    internal fun harvestSealedSubtypes(
        sourceContents: List<String>,
    ): LinkedHashMap<SealedParent, MutableList<Pair<String, String>>> {
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
     * SKIE-style flat aliases for sealed subtypes, appended onto the generated `Shared.swift`:
     * `public typealias <Parent><Subtype> = _ExportedKotlinPackages_<path>_<Parent>_<Subtype>` for
     * every subtype class the harvest finds, so Swift names `AdminInboxUiStateReady` instead of the
     * mangled class. Idempotent via a marker.
     *
     * This pass used to also regenerate SKIE's `onEnum(of:)`: a hand-rolled enum per sealed parent
     * with a synthetic `unknown` case, policed by a source-vs-Swift drift guard. Kotlin 2.4.20's
     * Swift Export emits `sealedType()` natively, returning an enum that is exhaustive by
     * construction (a dropped subtype is a Swift compile error, never a runtime fallthrough), so the
     * enum, the overload and the guard are gone. Only the aliases remain.
     *
     * @param sharedContent the `Shared.swift` contents the aliases are appended to.
     * @param sourceContents the `Shared.swift` + `ListenupContract.swift` contents to harvest
     *   subtypes from.
     * @return the rewritten `Shared.swift` and `count` = number of sealed parents aliased.
     */
    fun appendSealedSubtypeAliases(
        sharedContent: String,
        sourceContents: List<String>,
    ): PatchOutcome {
        if (sharedContent.contains(SEALED_ALIAS_MARKER)) return PatchOutcome(sharedContent, 0)
        val sealedTypes = harvestSealedSubtypes(sourceContents)
        if (sealedTypes.isEmpty()) return PatchOutcome(sharedContent, 0)
        val builder = StringBuilder("\n$SEALED_ALIAS_MARKER\n")
        val emittedAlias = HashSet<String>()
        for ((parent, subtypes) in sealedTypes) {
            for ((subtype, className) in subtypes) {
                val alias = "${parent.name}$subtype"
                if (emittedAlias.add(alias)) builder.append("public typealias $alias = $className\n")
            }
        }
        return PatchOutcome(sharedContent + builder.toString(), sealedTypes.size)
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
        val lines = linesOf(content)
        val out = ArrayList<String>(lines.size)
        val caseMap = HashMap<String, String>()
        var changed = false
        // The brace depth the current enum opened at; -1 while outside any enum.
        var enumDepth = -1
        var depth = 0
        for (line in lines) {
            val rewritten = if (enumDepth < 0) line else camelCaseEnumLine(line, caseMap)
            if (rewritten != line) changed = true
            if (enumDepth < 0 && enumStart.containsMatchIn(line)) {
                enumDepth = depth
                caseMap.clear()
            }
            out.add(rewritten)
            depth += braceDelta(line)
            if (enumDepth >= 0 && depth <= enumDepth) enumDepth = -1
        }
        return if (changed) {
            PatchOutcome(out.joinToString("\n") + "\n", 1)
        } else {
            PatchOutcome(content, 0)
        }
    }

    private val enumStart = Regex("""^(\s*)public enum (\w+):""")
    private val caseDecl = Regex("""^(\s*)case ([A-Z][A-Z0-9_]+)\s*$""")

    /**
     * One line inside an enum body: a `SCREAMING_SNAKE` case declaration becomes camelCase (and is
     * remembered in [caseMap]); any other line has every `.CASE` reference to a remembered case
     * rewritten. Anything else comes back untouched.
     */
    private fun camelCaseEnumLine(
        line: String,
        caseMap: MutableMap<String, String>,
    ): String {
        val decl = caseDecl.matchEntire(line)
        if (decl != null) {
            val raw = decl.groupValues[2]
            val camel = toCamel(raw)
            if (camel == raw) return line
            caseMap[raw] = camel
            return "${decl.groupValues[1]}case $camel"
        }
        var rewritten = line
        for ((raw, camel) in caseMap) {
            if (rewritten.contains(".$raw")) rewritten = rewritten.replace(Regex("""\.$raw\b"""), ".$camel")
        }
        return rewritten
    }

    private fun toCamel(name: String): String {
        val camel =
            name
                .split("_")
                .mapIndexed { i, part ->
                    if (i == 0) part.lowercase() else part.lowercase().replaceFirstChar { it.uppercase() }
                }.joinToString("")
        return if (camel in swiftKeywords) "`$camel`" else camel
    }

    /**
     * Walks a generated SPM package dir, applies every transform to the right files, returns
     * per-pass counts keyed `patchSource` / `camelCase` / `flatTypealias` / `sealedAlias`. The only
     * File-touching code; mirrors [LocalizationArtifacts]'s role around [LocalizationGenerator].
     *
     * Order matches the original `doLast`: per-file passes first (`patchSource`, `camelCase`), then
     * the Shared.swift-append passes (`flatTypealias`, `sealedAlias`). A missing root yields
     * all-zero counts (the original returned 0 from each transform).
     */
    fun patchPackage(root: File): Map<String, Int> {
        if (!root.exists()) {
            return mapOf(
                "patchSource" to 0,
                "camelCase" to 0,
                "flatTypealias" to 0,
                "sealedAlias" to 0,
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

        val sealedAliasCount: Int
        if (sharedFile != null) {
            // Re-read source contents: the flat-typealias pass mutated Shared.swift above.
            val sealedSources = moduleSourceFiles(root).map { it.readText() }
            val outcome = appendSealedSubtypeAliases(sharedFile.readText(), sealedSources)
            if (outcome.content != sharedFile.readText()) sharedFile.writeText(outcome.content)
            sealedAliasCount = outcome.count
        } else {
            sealedAliasCount = 0
        }

        return mapOf(
            "patchSource" to patchSourceCount,
            "camelCase" to camelCaseCount,
            "flatTypealias" to flatTypealiasCount,
            "sealedAlias" to sealedAliasCount,
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
