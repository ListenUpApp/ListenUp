plugins {
    id("listenup.kmp.library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinxRpc)
}

kotlin {
    // JVM target — used by :server, and for the rpc-guard runtime + KSP-generated guards.
    jvm()

    android {
        namespace = "com.calypsan.listenup.contract"

        lint {
            checkDependencies = false
            disable += setOf("InvalidPackage", "ObsoleteLintCustomCheck")
        }
    }

    // Apple targets carried over from :shared so a future native macOS app can link
    // :contract through :app:sharedLogic's framework. No framework binary here — :app:sharedLogic
    // owns the framework export.
    iosArm64()
    iosSimulatorArm64()

    // linuxX64 — used by the native :server build (the Kotlin/Native server port). :contract is the
    // shared source of truth both sides read, so it must publish a linuxX64 artifact for the server's
    // commonMain to reference contract types (DTOs, @Rpc interfaces, AppError) on native.
    linuxX64()
    // linuxArm64 — the arm64 native server (Raspberry Pi / AWS Graviton self-host). Same arch-agnostic
    // actuals as linuxX64, shared via the synthesized linuxMain source set below.
    linuxArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.rpc.core)
            implementation(libs.ktor.io) // io.ktor.utils.io.ByteReadChannel (FileSource)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.io.bytestring)
            implementation(libs.kotlin.logging)
        }

        // RPC-guard runtime helpers (Mdc, CorrelationId) the KSP-generated
        // *Guarded decorators compile against. JVM-only.
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.slf4j)
        }

        // Contract round-trip tests: assert every @Serializable DTO survives a
        // JSON encode/decode through contractJson. Kotest FunSpec is canonical.
        commonTest.dependencies {
            implementation(libs.kotest.framework.engine)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5) // JVM-only runner; engine + assertions inherited from commonTest
            // logback-classic instead of slf4j-simple: the rpcguard helpers use MDC
            // (via kotlinx-coroutines-slf4j) which requires a backend that supports
            // Mapped Diagnostic Context. slf4j-simple always returns null for MDC.get().
            implementation(libs.logback.classic)
        }
    }
}

// Kotest uses JUnit 5 as its runner on JVM
tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest") {
    useJUnitPlatform()
}

dependencies {
    // :tools:rpc-guard-ksp scans the @Rpc interfaces in this module's commonMain and
    // emits the *Guarded decorators into the JVM compilation.
    add("kspJvm", project(":tools:rpc-guard-ksp"))
    // Generate the guard decorators for the native server too (Phase 5). Per-target (NOT
    // commonMain): the @Rpc interfaces are local to :contract here so source discovery works, and
    // keeping guards out of commonMain avoids forcing apple actuals / Swift-export pollution.
    add("kspLinuxX64", project(":tools:rpc-guard-ksp"))
    add("kspLinuxArm64", project(":tools:rpc-guard-ksp"))
}
