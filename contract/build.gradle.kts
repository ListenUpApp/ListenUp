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
    // :contract through :sharedLogic's framework. No framework binary here — :sharedLogic
    // owns the framework export.
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.rpc.core)
            implementation(libs.ktor.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.collections.immutable)
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
        }

        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5) // JVM-only runner; engine + assertions inherited from commonTest
        }
    }
}

// Kotest uses JUnit 5 as its runner on JVM
tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest") {
    useJUnitPlatform()
}

dependencies {
    // :rpc-guard-ksp scans the @Rpc interfaces in this module's commonMain and
    // emits the *Guarded decorators into the JVM compilation.
    add("kspJvm", project(":rpc-guard-ksp"))
}
