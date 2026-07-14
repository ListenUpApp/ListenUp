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
    alias(libs.plugins.googleServices) apply false

    // Quality Tools
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    // Note: Kover coverage is applied per-module on the JVM-pure modules only
    // (:server, :rpc-guard-ksp — see their build files). The androidKmpLibrary
    // modules (:contract, :sharedLogic, :sharedUI) remain uncovered: Kover is
    // incompatible with the com.android.kotlin.multiplatform.library plugin.
    // Extend coverage to them when upstream Kover supports that plugin.
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
        "$rootDir/contract/src/appleMain/kotlin",
        "$rootDir/contract/src/linuxMain/kotlin",
        "$rootDir/contract/src/nativeMain/kotlin",
        "$rootDir/sharedLogic/src/commonMain/kotlin",
        "$rootDir/sharedLogic/src/androidMain/kotlin",
        "$rootDir/sharedLogic/src/iosMain/kotlin",
        "$rootDir/sharedLogic/src/appleMain/kotlin",
        "$rootDir/sharedLogic/src/jvmMain/kotlin",
        "$rootDir/sharedLogic/src/jvmTest/kotlin",
        "$rootDir/sharedUI/src/commonMain/kotlin",
        "$rootDir/sharedUI/src/androidMain/kotlin",
        "$rootDir/sharedUI/src/desktopMain/kotlin",
        "$rootDir/desktopApp/src/main/kotlin",
        "$rootDir/server/src/commonMain/kotlin",
        "$rootDir/server/src/jvmMain/kotlin",
        "$rootDir/server/src/linuxX64Main/kotlin",
        "$rootDir/server/src/linuxMain/kotlin",
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
    // Explicit LF instead of the default GIT_ATTRIBUTES policy: that policy re-walks the whole
    // project tree (independent of the bounded targets below) to derive per-file endings, which
    // is pathological with linked worktrees present. Every platform we build on is LF.
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    kotlin {
        // Bounded to the module source roots rather than `**/*.kt`: Gradle's snapshotter WALKS
        // every directory the pattern could match before applying excludes, so a repo-wide glob
        // visits every linked worktree under `.worktrees/` + `.claude/worktrees/` (each a full
        // checkout) and turns a seconds-long format pass into an hours-long crawl. Explicit
        // top-level roots let the walker prune everything else outright. androidApp/ and iosApp/
        // carry no Kotlin sources (manifest-only wrapper / Swift); add a root here if a new
        // module gains Kotlin.
        target(
            "baselineprofile/src/**/*.kt",
            "build-logic/**/src/**/*.kt",
            "contract/src/**/*.kt",
            "desktopApp/src/**/*.kt",
            "rpc-guard-ksp/src/**/*.kt",
            "server/src/**/*.kt",
            "sharedLogic/src/**/*.kt",
            "sharedUI/src/**/*.kt",
        )
        targetExclude("**/build/**", "**/.worktrees/**", "**/.claude/**")
        ktlint(libs.versions.ktlint.get())
        // Suppress max-line-length for API files with complex Ktor builders
        suppressLintsFor {
            step = "ktlint"
            shortCode = "standard:max-line-length"
        }
    }
    kotlinGradle {
        // Bounded like the `kotlin` block above — a bare `**/*.gradle.kts` walks every linked
        // worktree before excludes apply.
        target(
            "*.gradle.kts",
            "androidApp/*.gradle.kts",
            "baselineprofile/*.gradle.kts",
            "build-logic/**/*.gradle.kts",
            "contract/*.gradle.kts",
            "desktopApp/*.gradle.kts",
            "rpc-guard-ksp/*.gradle.kts",
            "server/*.gradle.kts",
            "sharedLogic/*.gradle.kts",
            "sharedUI/*.gradle.kts",
        )
        targetExclude("**/build/**", "**/.worktrees/**", "**/.claude/**")
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

// VERIFY LOCAL — one-shot local equivalent of the Linux-lane CI gates (mirrors ci.yml Lint + Test (JVM)).
// Native server lane excluded (needs system libs + long native compiles) — run per CLAUDE.md "Pushing".
// Parity with ci.yml's task lists is pinned by VerifyLocalParityTest (build-logic/convention) — it
// fails this very task when the lists diverge.
tasks.register("verifyLocal") {
    group = "verification"
    description = "Runs the local equivalent of every Linux-lane CI gate (Lint + Test (JVM))."
    dependsOn(
        "spotlessCheck",
        "detekt",
        ":sharedUI:verifyStrings",
        ":sharedUI:verifyLicenses",
        ":sharedLogic:compileCommonMainKotlinMetadata",
        ":contract:jvmTest",
        ":sharedLogic:jvmTest",
        ":sharedLogic:testAndroidHostTest",
        ":server:jvmTest",
        ":sharedUI:testAndroidHostTest",
    )
    // build-logic is an included build (settings.gradle.kts: includeBuild("build-logic")) — a plain
    // ":build-logic:convention:test" string would be resolved against this build's project tree and
    // fail, so address the task through the composite-build API.
    dependsOn(gradle.includedBuild("build-logic").task(":convention:test"))
}
