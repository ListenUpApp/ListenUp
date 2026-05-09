plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false

    // Quality Tools
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    // Note: Kover code coverage is temporarily disabled due to incompatibility
    // with the new androidKmpLibrary plugin. Can be re-enabled when Kover
    // supports the new KMP configuration.
}

// Configure Java toolchain for all subprojects
// This ensures Java 17 is used even if you have Java 25 installed
allprojects {
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

// =============================================================================
// DETEKT - Static Analysis
// =============================================================================
detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    baseline = file("$rootDir/detekt-baseline.xml")
    parallel = true
    source.setFrom(
        "$rootDir/shared/src/commonMain/kotlin",
        "$rootDir/shared/src/androidMain/kotlin",
        "$rootDir/shared/src/iosMain/kotlin",
        "$rootDir/shared/src/jvmMain/kotlin",
        "$rootDir/shared/src/jvmTest/kotlin",
        "$rootDir/composeApp/src/commonMain/kotlin",
        "$rootDir/composeApp/src/androidMain/kotlin",
        "$rootDir/server/src/main/kotlin",
        "$rootDir/server/src/test/kotlin",
        "$rootDir/rpc-guard-ksp/src/main/kotlin",
        "$rootDir/rpc-guard-ksp/src/test/kotlin",
    )
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}")
    detektPlugins(project(":build-logic:detekt-rules"))
}

// Suppress SLF4J "no binding" warnings during SKIE processing
// Version must match libs.versions.toml slf4j version (2.0.17)
buildscript {
    dependencies {
        classpath("org.slf4j:slf4j-simple:2.0.17")
    }
}

// =============================================================================
// SPOTLESS - Code Formatting
// =============================================================================
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
        // Suppress max-line-length for API files with complex Ktor builders
        suppressLintsFor {
            step = "ktlint"
            shortCode = "standard:max-line-length"
        }
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
}
