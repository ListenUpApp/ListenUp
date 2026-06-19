import co.touchlab.skie.configuration.SuppressSkieWarning

plugins {
    id("listenup.kmp.library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.mokkery)
    alias(libs.plugins.skie)
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

            // Export Koin so it's accessible from Swift
            export(libs.koin.core)
            export(projects.contract)
        }
    }

    // macOS targets
    macosArm64()

    applyDefaultHierarchyTemplate()

    // TODO: Enable Native Swift Export when Gradle API is available
    // Swift Export is experimental in Kotlin 2.3.0-Beta2 but not yet exposed in Gradle plugin
    // For now, we'll use traditional framework export which still works great

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
            implementation(libs.ktor.resources) // @Resource annotation for REST surface mirror

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

            api(libs.koin.core)
            implementation(libs.kotlin.logging)

            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.palette.ktx)
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
            // can compile against them. The desktop client carries them on its classpath
            // but never calls them; the server provides Micrometer at runtime.
            implementation(libs.micrometer.core)
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
            // Ktor server + Exposed are `implementation` deps of `:server`, so consuming
            // server symbols from `:shared:jvmTest` requires them on the test classpath
            // explicitly. Confined to jvmTest — production is unaffected.
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.server.sse)
            implementation(libs.ktor.server.auth)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.exposed.jdbc)
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

// Wire KSP for Room - platform-specific targets only
// Note: kspCommonMainMetadata is intentionally omitted to avoid generating
// an actual object that conflicts with the expect declaration.
// Platform-specific KSP tasks generate the actual implementations.
//
// rpc-guard-ksp runs on kspJvm only: it discovers @Rpc interfaces in commonMain
// and emits *Guarded decorator classes + RpcGuardDispatcher into the JVM source
// set. These generated classes compile against the rpcguard runtime helpers in
// jvmMain (RpcGuardMetrics, withMdc, currentCorrelationId) and are consumed by
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

// SKIE configuration for enhanced Swift interop
skie {
    isEnabled = true

    // Enable Flow support - converts Kotlin Flow to Swift AsyncSequence
    features {
        // Enables StateFlow/SharedFlow → Swift async/await
        group {
            coroutinesInterop.set(true)
        }
        // Generates Swift-friendly sealed class handling
        group {
            enableSwiftUIObservingPreview.set(true)
        }
        // Suppress description property name collision warnings globally.
        // Kotlin "description" properties collide with ObjC KotlinBase.description().
        // SKIE renames them to description_ in Swift which is acceptable.
        group {
            SuppressSkieWarning.NameCollision(true)
        }
    }
}
