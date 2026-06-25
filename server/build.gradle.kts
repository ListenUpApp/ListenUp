import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
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
    // Pin compilation to JDK 21 so a newer local/daemon JDK can't shift validation.
    jvmToolchain(21)

    jvm()
    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                val libargon2 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/libargon2.def"))
                }
                val sqlite3 by creating {
                    defFile(project.file("src/nativeInterop/cinterop/sqlite3.def"))
                }
            }
        }
    }

    // Mirror the `listenup.jvm` convention plugin: apply the project-wide compiler-args triple
    // to every compilation, set JVM_21 bytecode on the JVM target, and allow consuming the
    // pre-release-marked :contract classfiles (compiled against Kotlin 2.3.20).
    // NOTE: the first three args below mirror LISTENUP_FREE_COMPILER_ARGS (build-logic/convention/src/main/kotlin/ListenUpCompilerArgs.kt).
    // That constant isn't importable from a leaf module's build script, so they're inlined here —
    // keep them in sync. Extract a `listenup.kmp.server` convention plugin if a second
    // native-server module ever needs the same setup.
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-Xreturn-value-checker=check",
            "-Xexplicit-backing-fields",
            "-Xskip-prerelease-check",
        )
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

    sourceSets {
        val commonMain by getting {
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
            }
        }

        val linuxX64Main by getting {
            dependencies {
                // SQLDelight native SQLite driver (SQLiter — dynamically links system libsqlite3)
                implementation(libs.sqldelight.driver.native)
                implementation(libs.cryptography.provider.openssl3.prebuilt)
            }
        }

        val jvmMain by getting {
            dependencies {
                // Ktor server core + engine
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)

                // Ktor plugins (core)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.ktor.server.resources)
                implementation(libs.ktor.server.status.pages)
                implementation(libs.ktor.server.call.logging)
                implementation(libs.ktor.server.auth)
                implementation(libs.ktor.server.sse)
                implementation(libs.ktor.server.partial.content)
                implementation(libs.ktor.server.auto.head.response)

                // Ktor plugins (call-id, rate limit)
                implementation(libs.ktor.server.call.id)
                implementation(libs.ktor.server.rate.limit)

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

                // Koin
                implementation(libs.koin.core)
                implementation(libs.koin.ktor)

                // kotlinx.rpc — server-side runtime (proxies generated by :contract's plugin)
                implementation(libs.kotlinx.rpc.core)
                implementation(libs.kotlinx.rpc.krpc.server)
                implementation(libs.kotlinx.rpc.krpc.ktor.server)
                implementation(libs.kotlinx.rpc.krpc.serialization.json)

                // Logging + Metrics
                implementation(libs.kotlinx.coroutines.slf4j)

                // Ktor HTTP client — used by AudibleClient to call the Audible catalog API.
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.content.negotiation)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotest.framework.engine)
                implementation(libs.kotest.assertions.core)
            }
        }

        val jvmTest by getting {
            dependencies {
                // Test deps
                // In-process client<->server end-to-end fixtures (server/src/jvmTest/.../e2e/) drive the
                // real client auth stack against the embedded server. Test classpath only — the
                // production :server artifact depends on :contract alone.
                implementation(projects.sharedLogic)
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

val generateMigrationCatalog by tasks.registering {
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
    // Wipe and recreate before each run: starts every run from empty (bounding disk use) and
    // reclaims leftovers from any previous run whose workers were force-killed.
    doFirst {
        workingDir.mkdirs()
        testTmpDir.deleteRecursively()
        testTmpDir.mkdirs()
    }
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
