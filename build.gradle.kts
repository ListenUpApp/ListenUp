plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false

    // Quality Tools
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    // Note: Kover code coverage is temporarily disabled due to incompatibility
    // with the new androidKmpLibrary plugin. Can be re-enabled when Kover
    // supports the new KMP configuration.
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
        "$rootDir/contract/src/commonMain/kotlin",
        "$rootDir/contract/src/androidMain/kotlin",
        "$rootDir/contract/src/iosMain/kotlin",
        "$rootDir/contract/src/jvmMain/kotlin",
        "$rootDir/sharedLogic/src/commonMain/kotlin",
        "$rootDir/sharedLogic/src/androidMain/kotlin",
        "$rootDir/sharedLogic/src/iosMain/kotlin",
        "$rootDir/sharedLogic/src/jvmMain/kotlin",
        "$rootDir/sharedLogic/src/jvmTest/kotlin",
        "$rootDir/sharedUI/src/commonMain/kotlin",
        "$rootDir/sharedUI/src/androidMain/kotlin",
        "$rootDir/server/src/main/kotlin",
        "$rootDir/server/src/test/kotlin",
        "$rootDir/rpc-guard-ksp/src/main/kotlin",
        "$rootDir/rpc-guard-ksp/src/test/kotlin",
        "$rootDir/web/src/main/kotlin",
        "$rootDir/web/src/test/kotlin",
    )
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}")
    detektPlugins("com.calypsan.listenup.build-logic:detekt-rules:0.0.1")
}

// Suppress SLF4J "no binding" warnings during SKIE processing (build-time only).
// Version comes from the catalog so it can't drift from the runtime slf4j version.
buildscript {
    dependencies {
        classpath(libs.slf4j.simple)
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
