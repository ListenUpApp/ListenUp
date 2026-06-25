plugins {
    id("listenup.kmp.library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.mokkery)
}

kotlin {
    // JVM target for desktop (Windows/Linux)
    jvm()

    // Android target using new AGP 9.0-compatible plugin
    android {
        namespace = "com.calypsan.listenup.client.shared"

        // Enable Android unit tests (runs on JVM, connected to commonTest)
        withHostTest {}

        lint {
            checkDependencies = false // Avoid KMP dependency double-scanning
            disable +=
                setOf(
                    "InvalidPackage", // False positives on multiplatform expect/actual
                    "ObsoleteLintCustomCheck", // Third-party KMP libs may trigger this
                )
        }
    }

    // iOS targets
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            binaryOption("bundleId", "com.calypsan.listenup.shared")

            export(projects.contract)
        }
    }

    // macOS targets
    macosArm64()

    applyDefaultHierarchyTemplate()

    // Native Swift Export — the shipping iOS interop surface (SKIE is gone). The Xcode build
    // phase drives `embedSwiftExportForXcode`; a post-generation patcher (further down) makes the
    // output idiomatic. `flattenPackage` requests the short package prefix; where Kotlin 2.4.0
    // doesn't actually collapse it, the patcher's typealias layer does.
    @OptIn(org.jetbrains.kotlin.gradle.swiftexport.ExperimentalSwiftExportDsl::class)
    swiftExport {
        moduleName = "Shared"
        flattenPackage = "com.calypsan.listenup"
    }

    // Opt-ins required for the Swift Export bridge to compile the generated Kotlin glue.
    // Each names an experimental API that some exported (or transitively reachable) declaration
    // touches; without the opt-in the generated bridge source fails to compile.
    compilerOptions {
        optIn.addAll(
            "kotlin.ExperimentalStdlibApi",
            "kotlin.time.ExperimentalTime",
            "kotlinx.cinterop.BetaInteropApi",
            "kotlinx.coroutines.InternalCoroutinesApi",
            "io.ktor.util.InternalAPI",
            "io.ktor.utils.io.InternalAPI",
            "org.koin.core.annotation.KoinInternalApi",
            "kotlinx.io.InternalIoApi",
            "kotlinx.io.unsafe.UnsafeIoApi",
        )
    }

    sourceSets {
        // Default hierarchy template provides: commonMain -> nativeMain -> appleMain -> {iosMain, macosMain}
        // We just get references to configure dependencies
        val appleMain by getting

        commonMain.dependencies {
            api(projects.contract)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.auth)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlincrypto.sha2)
            implementation(libs.kotlinx.rpc.core)
            implementation(libs.kotlinx.rpc.krpc.client)
            implementation(libs.kotlinx.rpc.krpc.ktor.client)
            implementation(libs.kotlinx.rpc.krpc.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.atomicfu)

            // `implementation`, not `api`: Koin's `Module` type must not reach the public
            // export surface. A public `Module` val drags `ParametersHolder.initialize(
            // MutableList<…>)` into the Swift Export bridge and double-emits the
            // `kotlin.collections.MutableList` LLVM global, crashing the native link.
            implementation(libs.koin.core)
            implementation(libs.kotlin.logging)

            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.work.runtime.ktx)
        }

        // Apple (iOS + macOS) shared dependencies
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.jmdns) // mDNS server discovery
            // Note: SLF4J backend provided by consuming app (desktopApp uses logback)

            // RPC guard runtime helpers — used by rpc-guard-ksp generated code.
            // These live in jvmMain so KSP-generated *Guarded classes (also jvmMain)
            // can compile against them. The desktop client carries them on its
            // classpath but never calls them.
            implementation(libs.kotlinx.coroutines.slf4j)
        }

        jvmTest.dependencies {
            // logback-classic instead of slf4j-simple: the rpcguard helpers use MDC
            // (via kotlinx-coroutines-slf4j) which requires a backend that supports
            // Mapped Diagnostic Context. slf4j-simple always returns null for MDC.get().
            implementation(libs.logback.classic)
            implementation(libs.androidx.room.testing) // MigrationTestHelper for W4.5+
            implementation(libs.kotest.runner.junit5) // JVM-only runner; engine + assertions inherited from commonTest
            // G1: Konsist — architectural assertions on the contract boundary.
            // Rules scan the entire repo from a single test source set on JVM.
            implementation(libs.konsist)
            // Tier 3 e2e fixture wires `:shared`'s client engine against the real
            // `:server` testApplication in-process. Test-classpath only — production
            // jvmMain has no server dep.
            implementation(project(":server"))
            // Ktor server is an `implementation` dep of `:server`, so consuming server symbols
            // from `:shared:jvmTest` requires it on the test classpath explicitly. Confined to
            // jvmTest — production is unaffected.
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.server.sse)
            implementation(libs.ktor.server.auth)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.koin.ktor)
            // kotlinx-rpc ktor-server DSL — the Tier 3 e2e harness mounts the
            // `BookService` RPC route alongside `syncRoutes()` so client→server
            // BookService calls round-trip in-process. Test-classpath only.
            implementation(libs.kotlinx.rpc.krpc.ktor.server)
        }

        commonTest.dependencies {
            implementation(libs.mokkery.runtime)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotest.framework.engine)
            implementation(libs.kotest.assertions.core)
        }
    }
}

// Kotest uses JUnit 5 as its runner on JVM
tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    // The jvmTest suite boots many in-process Ktor servers (Flyway + Room + RPC e2e tests) in a
    // single non-forked worker. Gradle's default Test heap (512m) is too small for that and OOMs.
    maxHeapSize = "2g"
}

// Define Room Schema location (optional but good practice)
room {
    schemaDirectory("$projectDir/schemas")
}

// NOTE: stately-concurrent-collections (pulled transitively by koin-core) ships a
// ConcurrentMutableList/Map that Koin uses at runtime on Kotlin/Native — `KoinPlatformTools.
// safeHashMap()` constructs `ConcurrentMutableMap` during `startKoin`, so it MUST be on the
// classpath or the app aborts at launch with an IrLinkageError. It must NOT be excluded.
// The Swift Export `MutableList` link collision it once caused is prevented structurally
// instead: the DI `Module` vals are `internal`, so koin-core never reaches the exported Swift
// surface and its mutable-collection bridge is never generated.

// ---- Swift Export (Alpha) codegen workarounds ---------------------------------------------
// Kotlin 2.4.0 Swift export emits some uncompilable Swift for transitively-reachable dependency
// types. Two bug classes are patched here, both behavior-preserving and removable upstream:
//
//   Class 1 — unavailable operator calling an unavailable helper. An operator that maps to a
//   Swift `@available(*, unavailable)` declaration (e.g. `CoroutineDispatcher.+`, whose Kotlin
//   `@Deprecated(level = ERROR)` becomes `unavailable`) has its body call an equally-unavailable
//   `_helper`; Swift rejects calling an unavailable symbol even from inside another. These decls
//   are uncallable from Swift, so rewriting only those bodies to `fatalError()` is safe. The
//   transform is annotation-scoped + brace-aware — available operators (Timestamp, Duration,
//   Comparable, …) that legitimately delegate to `this._helper` are left intact.
//
//   Class 2 — function referencing an undefined same-module type. Swift export references a
//   nested sealed-interface type it never emits (e.g. `LocalDate.Companion.Format(block:)`'s
//   closure parameter `DateTimeFormatBuilder.WithDate`). Such a function can't compile and isn't
//   part of any API we call from Swift, so the whole declaration is deleted (brace-aware).
fun patchGeneratedSwiftExportSources(root: java.io.File): Int {
    if (!root.exists()) return 0
    val unavailable = Regex("""@available\(\*, *unavailable""")
    val signatureClose = Regex("""\)\s*(->[^{]*)?\{\s*$""")
    val helperCall = Regex("""^(\s*)this\._[A-Za-z]+\(.*""")
    val typeDef = Regex("""\b(?:class|protocol|struct|enum|typealias|extension)\s+([A-Za-z0-9_]+)""")
    val funcStart = Regex("""^\s*(?:public|package|open|final|static|\s)*func\s""")
    var patchedFiles = 0
    root.walkTopDown().filter { it.isFile && it.extension == "swift" }.forEach { file ->
        val module = file.parentFile.name
        val lines = file.readLines()
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

        // Class 3 — rename a generated `description` Kotlin property to `description_`. Swift
        // export emits `public var description: <T>` for any Kotlin `description` field, which
        // collides with the inherited `KotlinRuntime.KotlinBase.description: String` (different
        // type → an illegal override). SKIE renamed the same field to `description_`; matching
        // that keeps existing Swift call sites stable. Guarded by the property's own
        // `_description_get` getter so framework `description` declarations are never touched.
        val descriptionProperty = Regex("""^(\s*)public var description: (.+)$""")
        for (idx in out.indices) {
            val match = descriptionProperty.matchEntire(out[idx]) ?: continue
            val getterNearby = (idx + 1..minOf(idx + 3, out.lastIndex)).any { out[it].contains("_description_get(") }
            if (getterNearby) {
                out[idx] = "${match.groupValues[1]}public var description_: ${match.groupValues[2]}"
                changed = true
            }
        }

        if (changed) {
            file.writeText(out.joinToString("\n") + "\n")
            patchedFiles++
        }
    }
    return patchedFiles
}

// ---- Swift Export flat-name typealias layer -----------------------------------------------
// `flattenPackage` does NOT actually flatten in Kotlin 2.4.0 — every exported type is nested as
// `ExportedKotlinPackages.com.calypsan.listenup.<pkg>.<Type>`, so `import Shared; Book` can't
// resolve. SKIE gave callers flat names; to match that, append a top-level `public typealias`
// for every exported type onto the generated `Shared.swift` (which `@_exported`s
// `ExportedKotlinPackages` and imports `ListenupContract`, so both modules' types are in scope).
// Idempotent via a marker. Name collisions across packages resolve to the `client.domain.model`
// (then any `client.domain`) variant; remaining ambiguous names are skipped and stay qualified.
fun appendFlatTypealiases(root: java.io.File): Int {
    if (!root.exists()) return 0
    val marker = "// --- swift-export flat typealias layer (generated) ---"
    val sharedFile =
        root.walkTopDown().firstOrNull { it.name == "Shared.swift" && it.parentFile.name == "Shared" } ?: return 0
    if (sharedFile.readText().contains(marker)) return 0
    val sourceFiles =
        listOf("Shared", "ListenupContract").mapNotNull { module ->
            root.walkTopDown().firstOrNull { it.name == "$module.swift" && it.parentFile.name == module }
        }
    val extensionRe = Regex("""^extension ExportedKotlinPackages\.([A-Za-z0-9_.]+?)(?:\s+where\b.*)?\s*\{""")
    val typeRe = Regex("""^\s+public (?:final class|class|enum|protocol|struct|actor) ([A-Za-z_][A-Za-z0-9_]*)""")
    val packagesByName = HashMap<String, MutableSet<String>>()
    for (file in sourceFiles) {
        var packageNamespace: String? = null
        var depth = 0
        for (line in file.readLines()) {
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
    val builder = StringBuilder("\n$marker\n")
    var count = 0
    for ((name, namespaces) in packagesByName.toSortedMap()) {
        if (name == "Companion") continue
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
    sharedFile.appendText(builder.toString())
    return count
}

// ---- Swift Export sealed-class enum support (the `onEnum(of:)` exhaustive-switch helper) -----
// Swift export maps a Kotlin sealed class to `protocol <Name>` + marker `package protocol _<Name>`
// + one `public final class _ExportedKotlinPackages_<path>_<Name>_<Subtype>` per subtype (each
// conforming to both). That gives no exhaustive Swift `switch`. SKIE gave callers `onEnum(of:)`
// returning a Swift enum; this regenerates that. Per sealed type, appended onto Shared.swift:
//   • a flat alias `<Name><Subtype>` for each subtype (SKIE's nested-subtype name), and
//   • `enum` (case = lowercased subtype, associated value = the subtype) + an `onEnum(of: <Name>)`
//     overload that `as?`-casts to each subtype. Idempotent via a marker.
fun appendSealedEnumSupport(root: java.io.File): Int {
    if (!root.exists()) return 0
    val marker = "// --- swift-export sealed-enum support (generated) ---"
    val sharedFile =
        root.walkTopDown().firstOrNull { it.name == "Shared.swift" && it.parentFile.name == "Shared" } ?: return 0
    if (sharedFile.readText().contains(marker)) return 0
    val sourceFiles =
        listOf("Shared", "ListenupContract").mapNotNull { module ->
            root.walkTopDown().firstOrNull { it.name == "$module.swift" && it.parentFile.name == module }
        }
    // A sealed subtype: `public final class <Class>: KotlinRuntime.KotlinBase,
    // ExportedKotlinPackages.<path>.<Parent>, ExportedKotlinPackages.<path>._<Parent> {`
    val subtypeRe =
        Regex(
            """^public final class (_ExportedKotlinPackages_\w+): KotlinRuntime\.KotlinBase, ExportedKotlinPackages\.([\w.]+)\.(\w+), ExportedKotlinPackages\.[\w.]+\._\3\b""",
        )
    val swiftKeywords =
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

    // parent (path, name) -> list of (subtypeSimpleName, subtypeClass)
    data class Parent(
        val path: String,
        val name: String,
    )
    val sealedTypes = LinkedHashMap<Parent, MutableList<Pair<String, String>>>()
    for (file in sourceFiles) {
        for (line in file.readLines()) {
            val m = subtypeRe.find(line) ?: continue
            val (className, path, parent) = m.destructured
            val subtype = className.substringAfterLast("_${parent}_")
            if (subtype.isBlank() || subtype == className) continue
            sealedTypes.getOrPut(Parent(path, parent)) { mutableListOf() }.add(subtype to className)
        }
    }
    if (sealedTypes.isEmpty()) return 0
    val builder = StringBuilder("\n$marker\n")
    val emittedAlias = HashSet<String>()
    var count = 0
    for ((parent, subtypes) in sealedTypes) {
        val enumName = "OnEnum_${parent.path.replace('.', '_')}_${parent.name}"
        // subtype flat aliases (SKIE's `<Parent><Subtype>` name)
        for ((subtype, className) in subtypes) {
            val alias = "${parent.name}$subtype"
            if (emittedAlias.add(alias)) builder.append("public typealias $alias = $className\n")
        }
        // enum
        builder.append("public enum $enumName {\n")
        for ((subtype, className) in subtypes) {
            val raw = subtype.replaceFirstChar { it.lowercase() }
            val case = if (raw in swiftKeywords) "`$raw`" else raw
            builder.append("    case $case($className)\n")
        }
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
        builder.append("    fatalError(\"non-exhaustive sealed type ${parent.name}\")\n}\n")
        count++
    }
    sharedFile.appendText(builder.toString())
    return count
}

// ---- Swift Export elegance: idiomatic camelCase enum cases -----------------------------------
// Swift export emits Kotlin enum entries with their raw `SCREAMING_SNAKE` names (`.SYSTEM`,
// `.FIRST_NAME`), which is jarring in Swift. Rewrite the generated case identifiers to camelCase
// (`.system`, `.firstName`) so call sites read idiomatically. Only the `case X` declarations and
// `.X` case references inside the enum are touched — the `"X"` string literals in the generated
// `description`/`init?(_:)` (which round-trip the Kotlin name on the wire) and the `com_…_X()` C
// bridge functions are left exactly as-is, so behavior is identical. Scoped per enum: only `.X`
// where `X` is a declared case of that same enum is rewritten. Idempotent (already-camelCased
// cases don't match the `SCREAMING_SNAKE` shape).
fun camelCaseKotlinEnumCases(root: java.io.File): Int {
    if (!root.exists()) return 0
    val sourceFiles =
        listOf("Shared", "ListenupContract").mapNotNull { module ->
            root.walkTopDown().firstOrNull { it.name == "$module.swift" && it.parentFile.name == module }
        }
    val enumStart = Regex("""^(\s*)public enum (\w+):""")
    val caseDecl = Regex("""^(\s*)case ([A-Z][A-Z0-9_]+)\s*$""")
    val swiftKeywords =
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

    fun toCamel(name: String): String {
        val camel =
            name
                .split("_")
                .mapIndexed { i, part ->
                    if (i == 0) part.lowercase() else part.lowercase().replaceFirstChar { it.uppercase() }
                }.joinToString("")
        return if (camel in swiftKeywords) "`$camel`" else camel
    }
    var patchedFiles = 0
    for (file in sourceFiles) {
        val lines = file.readLines()
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
        if (changed) {
            file.writeText(out.joinToString("\n") + "\n")
            patchedFiles++
        }
    }
    return patchedFiles
}

// Patch the generated SPM package sources after generation, before `BuildSPMPackage` compiles
// them (it dependsOn `GenerateSPMPackage`). Idempotent — re-running on already-patched output is
// a no-op, so it composes with Gradle's up-to-date checks.
//
// The Swift export SPM tasks (and this post-gen `doLast`, which references a script-level
// function and the project layout) are not configuration-cache compatible. Swift export is Alpha
// and these tasks only run on the iOS build lane, so opt that lane out of the configuration cache
// gracefully rather than serialize project/script references.
tasks.matching { it.name.endsWith("GenerateSPMPackage") }.configureEach {
    notCompatibleWithConfigurationCache(
        "Swift export (Alpha) SPM package generation and its post-gen codegen-bug patch are not configuration-cache compatible.",
    )
    val spmPackageDir = project.layout.buildDirectory.dir("SPMPackage")
    doLast {
        val patched = patchGeneratedSwiftExportSources(spmPackageDir.get().asFile)
        if (patched > 0) {
            logger.lifecycle("Swift export: patched $patched generated Swift file(s) for codegen bugs")
        }
        val camelCased = camelCaseKotlinEnumCases(spmPackageDir.get().asFile)
        if (camelCased > 0) {
            logger.lifecycle("Swift export: camelCased Kotlin enum cases in $camelCased file(s)")
        }
        val aliased = appendFlatTypealiases(spmPackageDir.get().asFile)
        if (aliased > 0) {
            logger.lifecycle("Swift export: appended $aliased flat typealiases for `import Shared`")
        }
        val sealed = appendSealedEnumSupport(spmPackageDir.get().asFile)
        if (sealed > 0) {
            logger.lifecycle("Swift export: appended onEnum support for $sealed sealed type(s)")
        }
    }
}

// Wire KSP for Room - platform-specific targets only
// Note: kspCommonMainMetadata is intentionally omitted to avoid generating
// an actual object that conflicts with the expect declaration.
// Platform-specific KSP tasks generate the actual implementations.
//
// rpc-guard-ksp runs on kspJvm only: it discovers @Rpc interfaces in commonMain
// and emits *Guarded decorator classes + RpcGuardDispatcher into the JVM source
// set. These generated classes compile against the rpcguard runtime helpers in
// jvmMain (withMdc, currentCorrelationId) and are consumed by
// :server transitively via the shared JVM artifact.
dependencies {
    // Android target
    add("kspAndroid", libs.androidx.room.compiler)

    // iOS targets
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)

    // macOS target
    add("kspMacosArm64", libs.androidx.room.compiler)

    // JVM target (desktop)
    add("kspJvm", libs.androidx.room.compiler)
}
