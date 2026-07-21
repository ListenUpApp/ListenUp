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
        val appleMain = getByName("appleMain")

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
    // Gradle's default Test heap (512m) is too small for this suite and OOMs. Two things need
    // headroom: the in-process Ktor servers (Flyway + Room + RPC e2e tests) in a single non-forked
    // worker, and the Konsist architectural rules, which hold a single shared PSI scope of the whole
    // production tree (~1.5k files) for the lifetime of the run (see konsist/KonsistScope.kt).
    maxHeapSize = "4g"
    // Pin the E2E retry ledger (written by HeavyweightE2ERetryExtension) to an absolute path under
    // this module's build/, so its location is workingDir-independent and identical in shape to the
    // server's — CI reads sharedLogic/build/e2e-retries.log.
    val e2eRetryLedger =
        layout.buildDirectory
            .file("e2e-retries.log")
            .get()
            .asFile
    systemProperty("listenup.e2eRetryLedger", e2eRetryLedger.absolutePath)
    // Truncate the ledger ONCE per task run so the retry extension is purely append-only — identical
    // model to :server:jvmTest (which forks workers every 25 classes and must not truncate per-worker).
    // This module runs a single non-forked worker, but keeping both on the same append-only contract
    // means the extension code is byte-identical and can't drift.
    doFirst {
        e2eRetryLedger.delete()
    }
}

// androidHostTest compiles commonTest sources (which include Konsist rules) but is not part
// of the jvmTest source set tree, so konsist isn't on its classpath by default. Add it
// directly to the generated configuration. The JUnit 5 runner mirrors jvmTest so that
// Kotest FunSpec specs execute identically on both host-test surfaces.
dependencies {
    "androidHostTestImplementation"(libs.konsist)
    "androidHostTestImplementation"(libs.kotest.runner.junit5)
}

tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    if (this is Test) {
        useJUnitPlatform()
        // Mirror jvmTest's heap: this surface runs the same Konsist rules, which hold a single
        // shared PSI scope of the whole production tree. The 512m default OOMs on it.
        maxHeapSize = "4g"
    }
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
// Kotlin 2.4.0 Swift export emits some uncompilable + non-idiomatic Swift for transitively-
// reachable dependency types. The four brace-aware, regex-based transforms that rewrite it now
// live in `build-logic`'s `SwiftExportSourcePatcher` — pure `String -> String` cores (unit-tested
// by `SwiftExportSourcePatcherTest` on the Linux `Test (JVM)` lane: `./gradlew
// :build-logic:convention:test`) plus a thin file-walking `patchPackage` wrapper. See that
// object's KDoc for the per-transform bug descriptions and which upstream fix retires each.
//
// Patch the generated SPM package sources after generation, before `BuildSPMPackage` compiles
// them (it dependsOn `GenerateSPMPackage`). Idempotent — re-running on already-patched output is
// a no-op, so it composes with Gradle's up-to-date checks.
//
// The Swift export SPM tasks (and this post-gen `doLast`, which references the project layout) are
// not configuration-cache compatible. Swift export is Alpha and these tasks only run on the iOS
// build lane, so opt that lane out of the configuration cache gracefully rather than serialize
// project/script references.
tasks.matching { it.name.endsWith("GenerateSPMPackage") }.configureEach {
    notCompatibleWithConfigurationCache(
        "Swift export (Alpha) SPM package generation and its post-gen codegen-bug patch are not configuration-cache compatible.",
    )
    // Never report UP-TO-DATE. KGP doesn't declare commonMain / `:contract` Kotlin sources as
    // inputs of SPM generation, so after a shared-code edit Gradle can skip this task — leaving
    // Xcode to link a STALE framework (old types, no compile error). The only prior workaround was
    // an undocumented `rm -rf sharedLogic/build/{SwiftExport,SPMPackage}`. The post-gen patcher
    // `doLast` below is idempotent (re-running on already-patched output is a no-op), so always
    // regenerating composes cleanly. Mirrors the always-verify gate in
    // `listenup.localization.gradle.kts`.
    outputs.upToDateWhen { false }
    // Patch only THIS task's own per-target SPM package, not the shared `build/SPMPackage` parent.
    // That dir holds one subdir per iOS target+config (`iosArm64/Debug`, `iosSimulatorArm64/Debug`, …),
    // and `patchPackage` selects a single `Shared.swift` via `firstOrNull`. Pointing it at the parent
    // lets an already-patched sibling target — whose sealed-enum marker makes `appendSealedEnumSupport`
    // return 0 — trip the `count > 0` floor below. That's the intermittent "matched 0 sealed types"
    // failure seen when a device build runs while a simulator package is already patched (or vice
    // versa). Scoping to this task's own `<target>/<config>` dir isolates the per-target patches.
    val packageStem = name.removeSuffix("GenerateSPMPackage") // e.g. "iosArm64Debug"
    val configName =
        listOf("Debug", "Release").firstOrNull(packageStem::endsWith)
            ?: error("Unexpected GenerateSPMPackage task name '$name' — cannot derive its SPM config dir.")
    val targetName = packageStem.removeSuffix(configName) // e.g. "iosArm64"
    val spmPackageDir = project.layout.buildDirectory.dir("SPMPackage/$targetName/$configName")
    doLast {
        val counts =
            com.calypsan.listenup.gradle.SwiftExportSourcePatcher
                .patchPackage(spmPackageDir.get().asFile)
        // Fail-fast on a silent codegen-shape drift. A Kotlin/Swift-Export bump that breaks the
        // sealed-enum or flat-typealias regexes would otherwise ship a bridge with no `onEnum`
        // support / no flat aliases on a green Linux PR. `patchSource` / `camelCase` can legitimately
        // match nothing on a clean tool version, so they are not floored here.
        check((counts["sealedEnum"] ?: 0) > 0) {
            "Swift Export patcher: appendSealedEnumSupport matched 0 sealed types — the generated output " +
                "shape likely changed (Kotlin/Swift-Export bump). The bridge would ship with no onEnum " +
                "support. Run `./gradlew :build-logic:convention:test` to localize the broken transform."
        }
        check((counts["flatTypealias"] ?: 0) > 0) {
            "Swift Export patcher: 0 flat typealiases emitted — generated shape drift. Run " +
                "`./gradlew :build-logic:convention:test` to localize the broken transform."
        }
        check((counts["appResult"] ?: 0) > 0) {
            "Swift Export patcher: AppResult accessor not emitted — the generated " +
                "_AppResult_Success/_Failure class shape likely changed (Kotlin/Swift-Export bump). The " +
                "two iOS call sites cast the raw mangled names and would break. Run " +
                "`./gradlew :build-logic:convention:test` to localize the broken transform."
        }
        logger.lifecycle("Swift export patcher: $counts")
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

    // JVM target (desktop)
    add("kspJvm", libs.androidx.room.compiler)
}

// ---- Swift Export worker classpath: IntellijCoroutines fix --------------------------------
// `embedSwiftExportForXcode` runs the Kotlin Analysis API in a Gradle worker whose classpath is
// resolved by the `swiftExportClasspathResolvable` configuration. That worker requires
// `kotlinx.coroutines.internal.intellij.IntellijCoroutines`, which ships ONLY in the JetBrains
// "intellij deps" coroutines variant — NOT in standard kotlinx-coroutines. The worker otherwise
// resolves standard `kotlinx-coroutines-core-jvm` (1.8.0, pulled transitively by the Analysis API),
// so a COLD Swift-export build crashes with `NoClassDefFoundError: …/IntellijCoroutines` (warm
// Konan/worker caches hide it — which is why the iOS lane looked green until a cache-miss rebuild).
// Redirect coroutines-core to the intellij variant on THIS worker configuration only; the app's own
// coroutines (and every other configuration) are untouched.
configurations.matching { it.name == "swiftExportClasspathResolvable" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlinx" &&
            (requested.name == "kotlinx-coroutines-core" || requested.name == "kotlinx-coroutines-core-jvm")
        ) {
            useTarget("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm:1.10.2-intellij-1")
            because(
                "Swift Export's Analysis API worker needs IntellijCoroutines (only in the intellij coroutines variant)",
            )
        }
    }
}

// ── Injected client version ─────────────────────────────────────────────────
// Generates ClientVersion.kt from the repo-root VERSION file at build time so
// DefaultClientIdentity announces the true marketing version (single source of
// truth shared with the server — see server/build.gradle.kts's mirror-image
// generateServerVersion task), rather than a hardcoded constant that silently
// goes stale.
val clientVersionFile = rootProject.layout.projectDirectory.file("VERSION")
val generatedClientVersionDir = layout.buildDirectory.dir("generated/version/kotlin")

val generateClientVersion =
    tasks.register("generateClientVersion") {
        group = "build"
        description = "Embeds the repo-root VERSION file into a generated ClientVersion.kt"
        val inFile = clientVersionFile
        val outDir = generatedClientVersionDir
        inputs.file(inFile).withPropertyName("versionFile")
        outputs.dir(outDir).withPropertyName("generated")
        doLast {
            val version =
                inFile.asFile
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.trim()
                    ?.removePrefix("v")
                    ?.ifEmpty { "0.0.1" }
                    ?: "0.0.1"
            val outFile =
                outDir.get().file("com/calypsan/listenup/client/domain/version/ClientVersion.kt").asFile
            outFile.parentFile.mkdirs()
            outFile.writeText(
                buildString {
                    appendLine("package com.calypsan.listenup.client.domain.version")
                    appendLine()
                    appendLine("// AUTO-GENERATED by generateClientVersion — do not edit.")
                    appendLine("internal const val CLIENT_VERSION: String = \"$version\"")
                },
            )
        }
    }

kotlin.sourceSets.commonMain
    .get()
    .kotlin
    .srcDir(generatedClientVersionDir)

// sharedLogic carries many KMP targets (jvm, android, iosArm64, iosSimulatorArm64, plus
// metadata compiles) — match every `compile*Kotlin*` task rather than enumerate them, so future
// targets pick up the dependency automatically. This also covers the Apple compile tasks without
// ever invoking them: Gradle only wires the dependency edge at configuration time, and those tasks
// simply aren't in the task graph on a Linux build. KSP tasks (Room's processor) also read
// commonMain sources and need the same edge — but their names are inconsistent (`kspKotlinJvm`
// contains "Kotlin", `kspAndroidMain` does not), so match ALL `ksp*` tasks, not just Kotlin-named
// ones. Gradle's implicit-dependency validation fails the build if any consumer is omitted.
tasks
    .matching {
        (it.name.startsWith("compile") && it.name.contains("Kotlin")) || it.name.startsWith("ksp")
    }.configureEach { dependsOn(generateClientVersion) }
