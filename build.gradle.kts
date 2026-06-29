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
    alias(libs.plugins.aboutlibraries) apply false

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
        "$rootDir/server/src/commonMain/kotlin",
        "$rootDir/server/src/jvmMain/kotlin",
        "$rootDir/server/src/linuxX64Main/kotlin",
        "$rootDir/server/src/commonTest/kotlin",
        "$rootDir/server/src/jvmTest/kotlin",
        "$rootDir/server/src/linuxX64Test/kotlin",
        "$rootDir/rpc-guard-ksp/src/main/kotlin",
        "$rootDir/rpc-guard-ksp/src/test/kotlin",
    )
}

dependencies {
    // Formatting is owned by Spotless/ktlint, so the detekt formatting plugin
    // (renamed detekt-formatting → detekt-rules-ktlint-wrapper in 2.0) is omitted.
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
        // Mirror the `kotlin` block: max-line-length is not enforced on build scripts. Beyond the
        // long dependency-coordinate / URL strings that motivate it for source, the embedded-Kotlin
        // parser ktlint uses to read .gradle.kts mis-measures lines in template-heavy blocks (e.g.
        // server's generateMigrationCatalog), so the rule false-positives on sub-120 lines.
        suppressLintsFor {
            step = "ktlint"
            shortCode = "standard:max-line-length"
        }
    }
}
