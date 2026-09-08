import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

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

// kctfork bundles a kotlin-compiler-embeddable that can lag the catalog after a Kotlin bump
// (e.g. kctfork 0.13.0 ships 2.4.0 while the catalog is on a newer patch). Force it to the catalog
// Kotlin so the return-value-guard tests compile with the same compiler production uses — and so
// ReturnValueGuardCanaryTest's version assertion stays green without waiting on a kctfork release.
dependencies {
    constraints {
        testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$expectedKotlinVersion")
    }
}

// In this build script rootDir is tools/build-logic/ (it's an included build), so the
// repository root is two levels up. VerifyLocalParityTest parses these repo-root
// files; hand the root over via a system property (the test JVM's working dir is
// not guaranteed) and register the parsed files as inputs so the parity test
// re-runs whenever CI, the root build script, or the Pushing docs change.
val repoRoot = rootDir.parentFile.parentFile

// TestSourceSetGatingTest walks the repo at RUNTIME to find test source sets, but Gradle re-runs a
// task only when a DECLARED input changes. With only the three parity files below declared, creating
// a new test source set left the task UP-TO-DATE and the one gate that exists to catch an unnoticed
// source set never ran — which is exactly how `app/sharedLogic/src/appleTest` reached main ungated.
//
// This declares the inventory of source-set directory NAMES (never their contents), so the gate
// re-runs when a source set appears or disappears and not merely because someone edited a spec.
//
// It MUST be a ValueSource, not a plain `val`. A configuration-time walk is captured in the
// configuration-cache entry and never re-evaluated, so the planted-source-set sabotage still came
// back UP-TO-DATE — the fix looked right and did nothing. Gradle re-runs a ValueSource on every
// build to decide whether the cached entry is still valid, which is the whole point of the type.
//
// The build script cannot import the test's own discovery rule (it is compiling the project that
// defines it), so the rule here is deliberately BROADER: every directory directly under any `src`,
// not just the test ones. An over-approximation can only cost an extra re-run; an under-approximation
// would reopen the hole, so the asymmetry is the point — do not narrow this to match the test.
abstract class SourceSetInventory : ValueSource<String, SourceSetInventory.Params> {
    interface Params : ValueSourceParameters {
        val repoRoot: org.gradle.api.file.DirectoryProperty
    }

    override fun obtain(): String {
        val root = parameters.repoRoot.get().asFile
        return listOf("app", "contract", "server", "tools")
            .map { root.resolve(it) }
            .filter { it.isDirectory }
            .flatMap { searchRoot -> searchRoot.walkTopDown().maxDepth(5).toList() }
            .filter { it.isDirectory && it.parentFile?.name == "src" }
            .filterNot { it.path.contains("/build/") }
            .map { it.relativeTo(root).path }
            .sorted()
            .joinToString(",")
    }
}

val sourceSetInventory =
    providers.of(SourceSetInventory::class) {
        parameters.repoRoot.set(layout.projectDirectory.dir(repoRoot.absolutePath))
    }

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
    inputs.property("sourceSetInventory", sourceSetInventory)
}
