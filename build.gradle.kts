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
    // Note: Kover coverage is applied per-module — see each module's build file.
    // It now covers the androidKmpLibrary modules (:contract, :app:sharedLogic,
    // :app:sharedUI) alongside the JVM-pure ones (:server, :tools:rpc-guard-ksp).
    // The long-standing note here claimed Kover was incompatible with the
    // com.android.kotlin.multiplatform.library plugin; that stopped being true at some
    // point before Kover 0.9.9, which generates per-variant artifacts (Android, JVM,
    // Desktop) for those modules — verified 2026-08-04 by applying it and reading the
    // reports. Coverage is a signal, not a gate: there is deliberately no verify rule.
}

// =============================================================================
// JS TOOLCHAIN - npm dependency overrides
// =============================================================================
// mocha 11.7.5 (pulled by the karma browser-test lane, which the Kotlin Multiplatform Gradle
// plugin hardcodes) depends on serialize-javascript ^6.0.2, and every version up to and
// including 7.0.2 carries GHSA-5c6j-r48x-rmvq — a high-severity RCE via unescaped
// RegExp.flags, itself an incomplete fix for CVE-2020-7660. It fails CI's dependency-review
// gate, which is set to fail-on-severity: high.
//
// Pinned rather than suppressed. This is build-time-only tooling that never reaches a shipped
// artifact, and the injection needs attacker-controlled input to serialize() — which here is
// our own test titles — so the practical risk is low. But "it's only build tooling" is exactly
// the reasoning that normalises supply-chain risk, and a pin costs nothing.
//
// Remove when mocha ships a release depending on >= 7.0.3 (mochajs/mocha#5781).
//
// ktor-client-core 3.5.2's js artifact declares an *exact* "ws": "8.20.1", which the Kotlin
// Multiplatform Gradle plugin copies into every generated package.json. Everything below 8.21.0
// carries GHSA-96hv-2xvq-fx4p / CVE-2026-48779 — a high-severity memory-exhaustion DoS where a
// flood of tiny frames allocates far past the documented limit and kills the process. Our other
// two ws consumers (engine.io, webpack-dev-server) already float to 8.21.3 and are unaffected;
// only Ktor's exact pin holds the tree back, and 8.21.x is a patch-level move for it.
//
// Remove when Ktor bumps its declared ws floor to >= 8.21.0.
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().apply {
        resolution("serialize-javascript", "7.0.3")
        resolution("ws", "8.21.3")
    }
}

// =============================================================================
// DETEKT - Static Analysis
// =============================================================================
detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/tools/detekt/detekt.yml")
    baseline = file("$rootDir/tools/detekt/baseline.xml")
    parallel = true
    source.setFrom(
        "$rootDir/contract/src/commonMain/kotlin",
        "$rootDir/contract/src/androidMain/kotlin",
        "$rootDir/contract/src/iosMain/kotlin",
        "$rootDir/contract/src/jvmMain/kotlin",
        "$rootDir/contract/src/appleMain/kotlin",
        "$rootDir/contract/src/linuxMain/kotlin",
        "$rootDir/contract/src/nativeMain/kotlin",
        "$rootDir/app/sharedLogic/src/commonMain/kotlin",
        "$rootDir/app/sharedLogic/src/androidMain/kotlin",
        "$rootDir/app/sharedLogic/src/iosMain/kotlin",
        "$rootDir/app/sharedLogic/src/appleMain/kotlin",
        "$rootDir/app/sharedLogic/src/jvmMain/kotlin",
        "$rootDir/app/sharedLogic/src/jvmTest/kotlin",
        "$rootDir/app/sharedUI/src/commonMain/kotlin",
        "$rootDir/app/sharedUI/src/androidMain/kotlin",
        "$rootDir/app/sharedUI/src/desktopMain/kotlin",
        "$rootDir/app/desktopApp/src/main/kotlin",
        "$rootDir/server/src/commonMain/kotlin",
        "$rootDir/server/src/jvmMain/kotlin",
        "$rootDir/server/src/linuxX64Main/kotlin",
        "$rootDir/server/src/linuxMain/kotlin",
        "$rootDir/server/src/commonTest/kotlin",
        "$rootDir/server/src/jvmTest/kotlin",
        "$rootDir/server/src/linuxX64Test/kotlin",
        "$rootDir/tools/rpc-guard-ksp/src/main/kotlin",
        "$rootDir/tools/rpc-guard-ksp/src/test/kotlin",
        // The js source sets. Absent until now, which meant every Kotlin file behind the web
        // client — the browser store actuals, and now the Compose HTML body — was silently
        // unlinted. A static-analysis gate that runs over less code than you think is worse
        // than one that fails, because it reports green either way.
        "$rootDir/contract/src/jsMain/kotlin",
        "$rootDir/app/sharedLogic/src/jsMain/kotlin",
        "$rootDir/app/webApp/src/jsMain/kotlin",
        "$rootDir/app/webApp/src/jsTest/kotlin",
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
        targetExclude("**/build/**", "**/.worktrees/**")
        ktlint(libs.versions.ktlint.get())
        // Suppress max-line-length for API files with complex Ktor builders
        suppressLintsFor {
            step = "ktlint"
            shortCode = "standard:max-line-length"
        }
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.worktrees/**")
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
        ":app:sharedUI:verifyStrings",
        ":app:sharedUI:verifyLicenses",
        ":app:sharedUI:verifySwiftStringKeys",
        ":app:sharedLogic:compileCommonMainKotlinMetadata",
        ":app:desktopApp:compileKotlin",
        // The web seam check — mirrors the "Compile shared modules for JS" step in ci.yml.
        // NB: no parentheses in this comment — VerifyLocalParityTest parses the dependsOn list
        // with a regex that stops at the first closing paren.
        ":contract:compileKotlinJs",
        ":app:sharedLogic:compileKotlinJs",
        ":contract:jvmTest",
        ":app:sharedLogic:jvmTest",
        ":app:sharedLogic:testAndroidHostTest",
        ":server:jvmTest",
        ":app:sharedUI:testAndroidHostTest",
        ":tools:rpc-guard-ksp:test",
    )
    // build-logic is an included build (settings.gradle.kts: includeBuild("tools/build-logic")) — a plain
    // ":build-logic:convention:test" string would be resolved against this build's project tree and
    // fail, so address the task through the composite-build API.
    dependsOn(gradle.includedBuild("build-logic").task(":convention:test"))
    dependsOn(gradle.includedBuild("build-logic").task(":detekt-rules:test"))
}
