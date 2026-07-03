import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    `kotlin-dsl`
}

dependencies {
    // Put KGP + the AGP KMP-library plugin on the convention classpath so the
    // precompiled script plugins can apply them by id.
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.android.kmpLibrary.gradlePlugin)

    // Runtime JSON parsing for the localization generator (parseToJsonElement;
    // no serialization compiler plugin needed).
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    // In-process Kotlin compilation for the return-value-guard canary
    // (ReturnValueGuardCanaryTest). Brings kotlin-compiler-embeddable pinned to
    // the same Kotlin as the catalog (asserted by the canary itself).
    testImplementation(libs.kctfork.core)
}

kotlin {
    jvmToolchain(21)
}

// The canary compares the embedded compiler against the catalog's Kotlin so a
// lagging kctfork can't quietly compile the fixture with a stale compiler. The
// type-safe `libs.versions.*` accessor doesn't resolve in a build-logic build
// script, so read the version via the VersionCatalogsExtension API instead.
val expectedKotlinVersion =
    extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")
        .findVersion("kotlin")
        .get()
        .requiredVersion
// In this build script rootDir is build-logic/ (it's an included build), so the
// repository root is one level up. VerifyLocalParityTest parses these repo-root
// files; hand the root over via a system property (the test JVM's working dir is
// not guaranteed) and register the parsed files as inputs so the parity test
// re-runs whenever CI, the root build script, or the Pushing docs change.
val repoRoot = rootDir.parentFile
tasks.withType<Test>().configureEach {
    systemProperty("listenup.expected.kotlin.version", expectedKotlinVersion)
    systemProperty("listenup.repo.root", repoRoot.absolutePath)
    inputs
        .files(
            repoRoot.resolve(".github/workflows/ci.yml"),
            repoRoot.resolve("build.gradle.kts"),
            repoRoot.resolve("CLAUDE.md"),
        ).withPropertyName("verifyLocalParityInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
