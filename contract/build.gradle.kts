import com.calypsan.listenup.gradle.failBelowDiscoveredTestCount
import com.calypsan.listenup.gradle.forwardKotestFilterProperties

plugins {
    id("listenup.kmp.library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinxRpc)
    alias(libs.plugins.kover)
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

    // js — the web seam check (canon chapters/05-build-order.md). Web is a primary-tier
    // platform, so its seam is checked pre-merge from the first core commit even though no
    // web client exists. Compile-only: no jsTest lane is wired.
    js { browser() }

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
    // Forward Kotest's native filter properties into the forked test JVM — see CLAUDE.md's
    // "Running a single test" section for the supported single-spec commands per lane.
    forwardKotestFilterProperties()
    // "Did this lane actually run?" guard, not a coverage target (canon-alignment plan A3) — a
    // collapsed classpath (a source set silently dropped from the compilation, a broken
    // dependency) still reports BUILD SUCCESSFUL with zero failures, which is worse than a run
    // that fails outright. Registered on the Gradle `Test` task itself rather than a Kotest
    // `afterProject` listener so it always reads the TASK TOTAL: Gradle aggregates every forked
    // worker's results into one root suite (`desc.parent == null`), whereas a Kotest-side listener
    // fires once per worker JVM and only sees that worker's slice — the same trap the
    // `io.kotest.provided.ProjectConfig` retry-ledger KDoc documents for `:server:jvmTest`'s
    // forked workers.
    //
    // The floor catches COLLAPSE, not attrition: 108 tests ran green on 2026-07-25, and the bar sits
    // far enough below that a normal deletion does not trip it. Deliberately not a ratchet — PR #1214
    // removed ~180 server tests in one legitimate change, so a floor set just under the current count
    // would fail honest work and train people to edit the number without reading it.
    failBelowDiscoveredTestCount(85, ":contract:jvmTest")
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
