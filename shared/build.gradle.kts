import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import co.touchlab.skie.configuration.SuppressSkieWarning

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.mokkery)
    alias(libs.plugins.skie)
    alias(libs.plugins.kotlinxRpc)
}

kotlin {
    // JVM target for desktop (Windows/Linux)
    jvm()

    // Android target using new AGP 9.0-compatible plugin
    androidLibrary {
        namespace = "com.calypsan.listenup.client.shared"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

        // Enable Android unit tests (runs on JVM, connected to commonTest)
        withHostTest {}

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-Xexpect-actual-classes",
                "-Xreturn-value-checker=check",
                "-Xexplicit-backing-fields",
            )
        }

        lint {
            warningsAsErrors = false
            abortOnError = true
            checkDependencies = false // Avoid KMP dependency double-scanning
            htmlReport = true
            xmlReport = true
            // KMP-specific accommodations
            disable +=
                setOf(
                    "InvalidPackage", // False positives on multiplatform expect/actual
                    "ObsoleteLintCustomCheck", // Third-party KMP libs may trigger this
                )
        }
    }

    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.addAll(
                        "-Xexpect-actual-classes",
                        "-Xreturn-value-checker=check",
                        "-Xexplicit-backing-fields",
                    )
                }
            }
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
        }
    }

    // macOS targets
    macosArm64()
    macosX64()

    applyDefaultHierarchyTemplate()

    // TODO: Enable Native Swift Export when Gradle API is available
    // Swift Export is experimental in Kotlin 2.3.0-Beta2 but not yet exposed in Gradle plugin
    // For now, we'll use traditional framework export which still works great

    sourceSets {
        // Default hierarchy template provides: commonMain -> nativeMain -> appleMain -> {iosMain, macosMain}
        // We just get references to configure dependencies
        val appleMain by getting
        val macosMain by getting

        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.resources) // @Resource annotation for REST surface mirror

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.rpc.core)
            implementation(libs.kotlinx.rpc.krpc.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.collections.immutable)
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
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.slf4j.simple) // Simple backend for tests only
            implementation(libs.androidx.room.testing) // MigrationTestHelper for W4.5+
            implementation(libs.kotest.runner.junit5) // JVM-only runner; engine + assertions inherited from commonTest
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
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
}

// Define Room Schema location (optional but good practice)
room {
    schemaDirectory("$projectDir/schemas")
}

// Wire KSP for Room - platform-specific targets only
// Note: kspCommonMainMetadata is intentionally omitted to avoid generating
// an actual object that conflicts with the expect declaration.
// Platform-specific KSP tasks generate the actual implementations.
dependencies {
    // Android target
    add("kspAndroid", libs.androidx.room.compiler)

    // iOS targets
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)

    // macOS targets
    add("kspMacosArm64", libs.androidx.room.compiler)
    add("kspMacosX64", libs.androidx.room.compiler)

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
