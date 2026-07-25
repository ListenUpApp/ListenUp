import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("listenup.kmp.server")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kover)
    // KSP must be applied BEFORE the Kotest plugin — Kotest 6 generates the Kotlin/Native test
    // entry point through KSP, and the processor is only registered if KSP is already present.
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotest)
}

sqldelight {
    databases {
        create("ListenUpDatabase") {
            packageName.set("com.calypsan.listenup.server.db.sqldelight")
        }
    }
}

group = "com.calypsan.listenup"
version = "0.0.1"

val isKtorDevelopment: Boolean = project.ext.has("development")

// Keep logback-classic off the classpath so our minimal ListenUpLogProvider stays the sole
// SLF4J provider (deterministic selection) and the logback jars never ship in the artifact.
configurations.all {
    exclude(group = "ch.qos.logback")
}

kotlin {
    jvm()
    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                create("libargon2") {
                    defFile(project.file("src/nativeInterop/cinterop/libargon2.def"))
                }
                create("sqlite3") {
                    defFile(project.file("src/nativeInterop/cinterop/sqlite3.def"))
                }
            }
        }
        // The native server executable (`server.kexe`). Entry point is the linuxX64Main `main()`,
        // which boots the shared Application.module() on the native Ktor CIO engine.
        binaries {
            executable {
                entryPoint = "com.calypsan.listenup.server.main"
                // Strip the symbol/debug tables from the release binary only (debug keeps them for
                // native debugging). `-s` passes through K/N's ld.lld; .dynsym and .eh_frame survive,
                // so dynamic linking and stack-unwinding are intact and the binary still boots. This
                // drops server.kexe ~33MB -> ~23MB and the distroless image ~58MB -> ~49MB.
                if (buildType == NativeBuildType.RELEASE) linkerOpts("-s")
            }
        }
        // Provide the system library search paths for the K/N bundled ld.lld linker, which has
        // no default sysroot and won't find libsqlite3/libargon2 without them. /usr/lib covers
        // distros that put the .so there directly (e.g. Arch); /usr/lib/x86_64-linux-gnu is the
        // Debian/Ubuntu multiarch location where apt's -dev packages install it. A non-existent
        // -L dir is harmless — the linker ignores it.
        binaries.all { linkerOpts("-L/usr/lib", "-L/usr/lib/x86_64-linux-gnu") }
    }
    // arm64 native server (Raspberry Pi / AWS Graviton self-host). Same two cinterops, same
    // entry point, same arch-agnostic actuals (shared via linuxMain). Cross-COMPILES from x86_64
    // (K/N auto-fetches the aarch64 toolchain + sysroot); the executable LINK needs aarch64
    // libsqlite3/libargon2 and runs on an arm64 runner in CI, not on this x86_64 dev box.
    linuxArm64 {
        compilations.getByName("main") {
            cinterops {
                create("libargon2") {
                    defFile(project.file("src/nativeInterop/cinterop/libargon2.def"))
                }
                create("sqlite3") {
                    defFile(project.file("src/nativeInterop/cinterop/sqlite3.def"))
                }
            }
        }
        binaries {
            executable {
                entryPoint = "com.calypsan.listenup.server.main"
                // Strip the release binary (see the linuxX64 note above); debug keeps its symbols.
                if (buildType == NativeBuildType.RELEASE) linkerOpts("-s")
            }
        }
        // arm64 multiarch dir (Debian/Ubuntu aarch64), present on an arm64 host / cross-sysroot.
        binaries.all { linkerOpts("-L/usr/lib", "-L/usr/lib/aarch64-linux-gnu") }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        // The `application` Gradle plugin is incompatible with KMP (KGP rejects it outright),
        // so the server's launchable JVM binary comes from the KMP/JVM binaries DSL instead.
        // This registers a `runJvm` task that runs `LauncherKt` against the jvm-main runtime.
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass.set("com.calypsan.listenup.server.LauncherKt")
                applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isKtorDevelopment")
            }
        }
    }

    // Synthesize the shared native hierarchy (nativeMain → linuxMain → {linuxX64Main, linuxArm64Main})
    // so both Linux arches share one set of arch-agnostic `actual`s in linuxMain. jvmMain stays a
    // direct child of commonMain.
    applyDefaultHierarchyTemplate()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(projects.contract)
                // SQLDelight — shared runtime + coroutines extensions (both JVM + native)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines)
                implementation(libs.cryptography.core)
                implementation(libs.kotlinx.serialization.json)
                // kotlinx-io — binary encoding for the mDNS codec (DnsCodec) and audiometa parser
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.io.bytestring)
                // kotlin-logging — structured logging (KMP: JVM + linuxX64)
                implementation(libs.kotlin.logging)
                implementation(libs.atomicfu)
                // Ktor server core + CIO engine + WebSocket plugin — moved to commonMain so
                // the native spike test can boot a real embeddedServer(CIO) on linuxX64.
                // All three artifacts have linuxX64 variants in Ktor 3.x.
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.json)
                // kotlinx.rpc server transport — moved to commonMain alongside ktor-server-cio
                // so the native spike test can register RPC services.
                implementation(libs.kotlinx.rpc.krpc.server)
                implementation(libs.kotlinx.rpc.krpc.ktor.server)
                implementation(libs.kotlinx.rpc.krpc.serialization.json)
                // Ktor plugins consumed by the commonMain `plugins/` + `foundation/` skeleton —
                // all publish linuxX64 variants. `ktor-server-call-logging` is the lone JVM-only
                // plugin and stays in jvmMain (see installCallLogging).
                implementation(libs.ktor.server.resources)
                implementation(libs.ktor.server.status.pages)
                implementation(libs.ktor.server.auth)
                implementation(libs.ktor.server.call.id)
                implementation(libs.ktor.server.rate.limit)
                // PartialContent — byte-range/seek support behind the file-response seam
                // (respondSeekable). Publishes a linuxX64 variant.
                implementation(libs.ktor.server.partial.content)
                // Koin DI — the di/ Koin modules + Application.module() are commonMain (Phase 5).
                // koin-core and koin-ktor (the install(Koin) plugin) both publish linuxX64 variants.
                implementation(libs.koin.core)
                implementation(libs.koin.ktor)
                // Ktor HTTP client — the metadata slice's Audible/iTunes/image client. The engine is a
                // per-platform seam (metadataHttpClient): CIO on jvmMain, Curl on linuxMain, because
                // Ktor CIO's TLS is unsupported for outbound HTTPS on Kotlin/Native (Phase 5-4c).
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
            }
        }

        getByName("linuxMain") {
            dependencies {
                // SQLDelight native SQLite driver (SQLiter — dynamically links system libsqlite3).
                // Shared by both Linux arches; both publish linuxArm64 + linuxX64 variants.
                implementation(libs.sqldelight.driver.native)
                implementation(libs.cryptography.provider.openssl3.prebuilt)
                // Ktor Curl client engine (libcurl) — the metadata slice's outbound HTTPS on native.
                // CIO's TLS is unsupported on Kotlin/Native. Publishes linuxX64 + linuxArm64 variants;
                // links the system libcurl (shipped into the runtime image — see Dockerfile.native).
                implementation(libs.ktor.client.curl)
            }
        }

        getByName("jvmMain") {
            dependencies {
                // Ktor server core + CIO engine + content-negotiation + serialization-json plus
                // the resources/status-pages/auth/sse/call-id/rate-limit plugins are in commonMain
                // (moved there for the native HTTP foundation). JVM-only Ktor plugins below:
                implementation(libs.ktor.server.call.logging)
                implementation(libs.ktor.server.auto.head.response)

                // Ktor CIO client engine — the JVM metadata client (metadataHttpClient.jvm.kt).
                implementation(libs.ktor.client.cio)

                // Persistence
                implementation(libs.sqlite.jdbc)

                // Date/time (was previously pulled in transitively via exposed-kotlin-datetime)
                implementation(libs.kotlinx.datetime)

                // SQLDelight JVM JDBC driver
                implementation(libs.sqldelight.driver.jvm)

                // Password hashing
                implementation(libs.password4j)

                // Kotlin-native cryptography (HMAC for AudioUrlSigner)
                implementation(libs.cryptography.provider.jdk)

                // kotlinx.rpc core — jvmMain only; krpc-server/krpc-ktor-server/krpc-serialization-json
                // are in commonMain (moved for the linuxX64 CIO-server spike).
                implementation(libs.kotlinx.rpc.core)

                // Logging + Metrics
                implementation(libs.kotlinx.coroutines.slf4j)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotest.framework.engine)
                implementation(libs.kotest.assertions.core)
                // FoundationSmokeTest: testApplication (REST/RPC/auth) + a real CIO client over
                // WebSocket for the kotlinx.rpc smoke (the server transport comes from commonMain).
                implementation(libs.ktor.server.test.host)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.websockets)
                implementation(libs.kotlinx.rpc.krpc.ktor.client)
                implementation(libs.kotlinx.rpc.krpc.client)
                implementation(libs.kotlinx.rpc.krpc.serialization.json)
            }
        }

        getByName("jvmTest") {
            dependencies {
                // Test deps
                // In-process client<->server end-to-end fixtures (server/src/jvmTest/.../e2e/) drive the
                // real client auth stack against the embedded server. Test classpath only — the
                // production :server artifact depends on :contract alone.
                implementation(projects.app.sharedLogic)
                implementation(libs.ktor.server.test.host)
                // Test-only: com.auth0 JWT lib (via ktor-server-auth-jwt) — an independent oracle that
                // forges adversarial tokens to cross-check the hand-rolled HS256 verifier. Not in production.
                implementation(libs.ktor.server.auth.jwt)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.kotest.runner.junit5)
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.property)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.koin.test)
                implementation(libs.kotlinx.rpc.krpc.client)
                implementation(libs.kotlinx.rpc.krpc.ktor.client)

                // F12 end-to-end auth fixture: real CIO HttpClient + bearer-auth plugin
                // for the DI-wired client graph that exercises the contract end-to-end.
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.auth)
                // MockEngine for AudibleClient unit tests.
                implementation(libs.ktor.client.mock)
                implementation(libs.mokkery.runtime)

                // Turbine — Flow assertions for the watcher tests.
                implementation(libs.turbine)

                // Konsist — architectural assertions for :server production code.
                implementation(libs.konsist)
            }
        }
    }
}

// ── Embedded migration catalog ────────────────────────────────────────────────
// Generates MigrationCatalog.kt from db/migration/*.sql at build time so the runner
// needs no runtime classpath scanning (native-image clean — replaces Flyway's scan).
val migrationsDir = layout.projectDirectory.dir("src/jvmMain/resources/db/migration")
val generatedMigrationsDir = layout.buildDirectory.dir("generated/migrations/kotlin")

val generateMigrationCatalog = tasks.register("generateMigrationCatalog") {
    group = "build"
    description = "Embeds db/migration/*.sql into a generated MigrationCatalog.kt"
    val inDir = migrationsDir
    val outDir = generatedMigrationsDir
    inputs.dir(inDir).withPropertyName("migrations")
    outputs.dir(outDir).withPropertyName("generated")
    doLast {
        val files =
            inDir.asFile
                .listFiles { f: java.io.File -> f.name.matches(Regex("""V\d+__.*\.sql""")) }
                ?.sortedBy { f: java.io.File ->
                    f.name
                        .substringAfter('V')
                        .substringBefore("__")
                        .toInt()
                }
                ?: emptyList()
        val md = MessageDigest.getInstance("SHA-256")

        fun ByteArray.toHex(): String = joinToString("") { b: Byte -> "%02x".format(b) }
        val entries =
            files.joinToString(",\n") { f: java.io.File ->
                val version =
                    f.name
                        .substringAfter('V')
                        .substringBefore("__")
                        .toInt()
                val name = f.name.substringAfter("__").removeSuffix(".sql")
                val content = f.readText()
                require(!content.contains("\"\"\"")) { "Migration ${f.name} contains a triple quote; cannot embed." }
                val checksum = md.digest(content.toByteArray()).toHex()
                val escaped = content.replace("\$", "\${'\$'}")
                "        Migration(version = $version, name = \"$name\", checksum = \"$checksum\", sql = \"\"\"$escaped\"\"\")"
            }
        val outFile = outDir.get().file("com/calypsan/listenup/server/db/MigrationCatalog.kt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            buildString {
                appendLine("package com.calypsan.listenup.server.db")
                appendLine()
                appendLine("// AUTO-GENERATED by generateMigrationCatalog — do not edit.")
                appendLine("internal object MigrationCatalog {")
                appendLine("    val all: List<Migration> = listOf(")
                appendLine(entries)
                appendLine("    )")
                appendLine("}")
            },
        )
    }
}

kotlin.sourceSets["commonMain"].kotlin.srcDir(generatedMigrationsDir)
tasks.named("compileKotlinJvm") { dependsOn(generateMigrationCatalog) }
tasks.named("compileKotlinLinuxX64") { dependsOn(generateMigrationCatalog) }
tasks.named("compileKotlinLinuxArm64") { dependsOn(generateMigrationCatalog) }

// ── Injected server version ───────────────────────────────────────────────────
// Generates ServerVersion.kt from the repo-root VERSION file at build time so the
// server announces its true marketing version (single source of truth shared with
// the clients), rather than a hardcoded constant that silently goes stale.
val versionFile = rootProject.layout.projectDirectory.file("VERSION")
val generatedVersionDir = layout.buildDirectory.dir("generated/version/kotlin")

val generateServerVersion = tasks.register("generateServerVersion") {
    group = "build"
    description = "Embeds the repo-root VERSION file into a generated ServerVersion.kt"
    val inFile = versionFile
    val outDir = generatedVersionDir
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
        val outFile = outDir.get().file("com/calypsan/listenup/server/api/ServerVersion.kt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            buildString {
                appendLine("package com.calypsan.listenup.server.api")
                appendLine()
                appendLine("// AUTO-GENERATED by generateServerVersion — do not edit.")
                appendLine("internal const val SERVER_VERSION: String = \"$version\"")
            },
        )
    }
}

kotlin.sourceSets["commonMain"].kotlin.srcDir(generatedVersionDir)
tasks.named("compileKotlinJvm") { dependsOn(generateServerVersion) }
tasks.named("compileKotlinLinuxX64") { dependsOn(generateServerVersion) }
tasks.named("compileKotlinLinuxArm64") { dependsOn(generateServerVersion) }

// KMP jvm-target compilations — the `application` plugin and the JavaExec helper tasks below
// run against these rather than the absent java-plugin `main`/`test` SourceSets.
val jvmMainCompilation =
    kotlin.targets
        .getByName("jvm")
        .compilations
        .getByName("main")
val jvmTestCompilation =
    kotlin.targets
        .getByName("jvm")
        .compilations
        .getByName("test")
val jvmMainRuntimeClasspath = files(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
val jvmTestRuntimeClasspath = files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    // Recycle the test JVM every 25 classes. The Ktor `testApplication` specs extract the
    // runtime classpath into the working dir on first use; left in a single long-lived worker
    // alongside the full spec count this intermittently corrupts class loading of synthetic
    // lambda inner classes (`<Spec>$1$1` NoClassDefFoundError) and eventually wedges the worker.
    // Periodic forking isolates each batch's classpath extraction and keeps the suite green.
    setForkEvery(25)
    // Redirect the working directory so any Ktor testApplication classpath-extraction
    // artefacts (META-INF/, io/) land under build/ rather than the project root.
    workingDir =
        layout.buildDirectory
            .dir("test-cwd")
            .get()
            .asFile
    // Redirect the test JVM's temp directory under build/ as well. Specs create temp SQLite
    // DBs (`listenup-test-*.db` + SQLite's `-wal`/`-shm` sidecars) and temp dirs via
    // `Files.createTempFile`/`createTempDirectory`; `deleteOnExit()` never covers the sidecars
    // and doesn't fire when a forked worker is killed, so these leaked into the system temp
    // dir (`/var/folders`, which macOS reports as "System Storage") — tens of GB over a few
    // days. Pointing `java.io.tmpdir` here keeps every temp artefact inside build/, so `clean`
    // wipes it and it never touches System Storage.
    val testTmpDir =
        layout.buildDirectory
            .dir("test-tmp")
            .get()
            .asFile
    systemProperty("java.io.tmpdir", testTmpDir.absolutePath)
    // Pin the E2E retry ledger (written by FlakyServerSpecRetryExtension) to an absolute path under
    // this module's build/ — the redirected workingDir above would otherwise land it under
    // build/test-cwd/build/, where the CI summary + artifact steps don't look.
    val e2eRetryLedger = layout.buildDirectory.file("e2e-retries.log").get().asFile
    systemProperty("listenup.e2eRetryLedger", e2eRetryLedger.absolutePath)
    // Wipe and recreate before each run: starts every run from empty (bounding disk use) and
    // reclaims leftovers from any previous run whose workers were force-killed.
    doFirst {
        workingDir.mkdirs()
        testTmpDir.deleteRecursively()
        testTmpDir.mkdirs()
        // Truncate the retry ledger ONCE per task run, before any worker forks. This task recycles
        // its worker JVM every 25 classes (setForkEvery above), and the retry extension's
        // beforeProject fires per-worker — so the extension only ever APPENDS; if it truncated, each
        // fresh worker would wipe the previous worker's retries and the ledger would keep only the
        // last batch. Truncating here (pre-fork, once) lets every worker's appends accumulate.
        e2eRetryLedger.delete()
    }
    // "Did this lane actually run?" guard, not a coverage target (canon-alignment plan A3) — a
    // collapsed classpath still reports BUILD SUCCESSFUL with zero failures, which is worse than
    // a run that fails outright. Deliberately NOT a Kotest `afterProject` listener (the pattern
    // `io.kotest.provided.ProjectConfig`'s retry ledger uses): this task forks a fresh worker JVM
    // every 25 classes (setForkEvery above), and such a listener fires once *per worker*, seeing
    // only that worker's slice. This `afterSuite` is registered on the Gradle `Test` task itself,
    // which aggregates every forked worker's results into one root suite (`desc.parent == null`)
    // — so it always sees the TASK TOTAL.
    //
    // The floor catches COLLAPSE, not attrition: 2,790 tests ran green on 2026-07-25, and the bar
    // sits far enough below that a normal deletion does not trip it. This lane is the reason the
    // margin is generous — PR #1214 legitimately removed ~180 tests here in one change, so a floor
    // set just under the current count would fail honest work and train people to edit the number
    // without reading it.
    val minDiscoveredTests = 2200
    afterSuite(
        KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
            if (desc.parent == null && result.testCount < minDiscoveredTests) {
                throw GradleException(
                    ":server:jvmTest discovered only ${result.testCount} tests, below the floor " +
                        "of $minDiscoveredTests. This usually means a source set silently dropped " +
                        "out of the compilation rather than a legitimate test deletion — " +
                        "investigate before lowering this floor.",
                )
            }
        }),
    )
}

// Give the native test binaries a real temp directory — the native mirror of the `java.io.tmpdir`
// redirect on jvmTest above. The Kotlin/Native test runner inherits the ambient environment, and
// TMPDIR is unset on a stock Linux login; kotlinx.io resolves
// `SystemTemporaryDirectory` as `getenv("TMPDIR") ?: getenv("TMP") ?: ""`, so with neither set it
// hands back the EMPTY path and every `createTempFileIn(SystemTemporaryDirectory, …)` dies with
// `mkdir failed: No such file or directory`. That is why the older native specs root their
// scratch dirs under $HOME instead. Pointing TMPDIR under build/ makes commonTest specs behave
// identically on both lanes and keeps the artefacts inside `clean`'s reach.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    val nativeTestTmpDir = layout.buildDirectory.dir("native-test-tmp/$name").get().asFile
    environment("TMPDIR", nativeTestTmpDir.absolutePath)
    doFirst {
        nativeTestTmpDir.deleteRecursively()
        nativeTestTmpDir.mkdirs()
    }
    // "Did this lane actually run?" guard, the native counterpart of the jvmTest floor above.
    // This lane earned one: before the Kotest plugin was applied it reported a green 28 tests while
    // 54 Kotest specs from commonTest sat on the classpath unexecuted — the K/N runner only ever
    // collected the `kotlin.test`-annotated ones, so a lane running a third of its own suite looked
    // indistinguishable from a healthy one. The floor catches COLLAPSE, not attrition: 82 tests ran
    // green on 2026-07-25, and the bar sits far enough below that honest deletion does not trip it.
    val minDiscoveredTests = 65
    afterSuite(
        KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
            if (desc.parent == null && result.testCount < minDiscoveredTests) {
                throw GradleException(
                    ":server:$name discovered only ${result.testCount} tests, below the floor " +
                        "of $minDiscoveredTests. This usually means a source set silently dropped " +
                        "out of the compilation, or the Kotest native entry point stopped being " +
                        "generated — investigate before lowering this floor.",
                )
            }
        }),
    )
}

// Regenerate the committed golden schema snapshot from the runner (the SSOT after Flyway's
// removal). Run after adding a migration: ./gradlew :server:generateSchemaSnapshot
tasks.register<JavaExec>("generateSchemaSnapshot") {
    group = "build"
    description = "Writes server/src/jvmTest/resources/golden/schema-current.txt from the current migrations."
    classpath = jvmTestRuntimeClasspath
    mainClass.set("com.calypsan.listenup.server.db.SchemaSnapshotMainKt")
    workingDir = rootProject.projectDir // so File("server/src/...") resolves from the repo root
}

val seedLibraryDir = layout.buildDirectory.dir("seed-library")

tasks.register<JavaExec>("generateSeedLibrary") {
    group = "demo"
    description = "Generates the synthetic audiobook library for the demo server (requires ffmpeg)."
    mainClass.set("com.calypsan.listenup.server.seed.SeedLibraryGenerator")
    classpath = jvmMainRuntimeClasspath
    args(seedLibraryDir.get().asFile.absolutePath)
    outputs.dir(seedLibraryDir)
}

tasks.register<JavaExec>("runDemo") {
    group = "demo"
    description = "Runs the server as a seeded demo server (generates the synthetic library first)."
    dependsOn("generateSeedLibrary")
    mainClass.set("com.calypsan.listenup.server.LauncherKt")
    classpath = jvmMainRuntimeClasspath
    environment("LISTENUP_SEED_PROFILE", "demo")
    environment("LISTENUP_LIBRARY_PATH", seedLibraryDir.get().asFile.absolutePath)
    environment("LISTENUP_DB_URL", "jdbc:sqlite:${layout.buildDirectory.get().asFile.absolutePath}/demo.db")
}

// The Kotlin/Native peer of `:server:run` — builds `server.kexe` (debug) and runs it in one command,
// no env vars required: `./gradlew :server:runNative`. Once a library folder is registered, the server
// re-scans it from its own DB on every boot, so the bare command is enough day to day. Optional Gradle
// properties — set once in `~/.gradle/gradle.properties` to never type them — seed/override config:
//   -Plibrary=/path/to/audiobooks   (seeds the library folder on first boot)
//   -Pport=8080                      (default 8080)
//   -Pseed=demo                      (demo seed profile)
tasks.register<Exec>("runNative") {
    group = "application"
    description = "Builds and runs the Kotlin/Native server (server.kexe)."
    dependsOn("linkDebugExecutableLinuxX64")
    commandLine(
        layout.buildDirectory
            .file("bin/linuxX64/debugExecutable/server.kexe")
            .get()
            .asFile.absolutePath,
    )
    environment("PORT", (findProperty("port") as String?) ?: "8080")
    (findProperty("library") as String?)?.let { environment("LISTENUP_LIBRARY_PATH", it) }
    (findProperty("seed") as String?)?.let { environment("LISTENUP_SEED_PROFILE", it) }
    // A long-running server: Ctrl-C (SIGINT) returns non-zero, which is a normal stop, not a failure.
    isIgnoreExitValue = true
}
