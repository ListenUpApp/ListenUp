plugins {
    id("listenup.kmp.library")
    id("listenup.localization")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.mokkery)
    alias(libs.plugins.aboutlibraries)
}

// Mokkery is used in desktopTest and androidHostTest — see
// sharedUI/src/desktopTest (DesktopPlaybackControllerTest) and
// sharedUI/src/androidHostTest (BrowseTreeProviderTest).
//
// Scope note: desktopTest's DesktopPlaybackControllerTest constructs a
// never-invoked `mock<PlaybackManager>()` to satisfy the controller's
// constructor; androidHostTest's BrowseTreeProviderTest is the second mokkery
// consumer. Both flags below are module-wide and therefore apply to every
// future mokkery mock in either source set; revisit them when a test surfaces a
// different mockability need (e.g. a real test that exercises PlaybackManager
// behaviour, at which point a hand-rolled FakePlaybackManager + an interface
// seam is the likely better answer).
//
// - `ignoreFinalMembers` lets us mock PlaybackManager (open class) without
//   having to mark each member as `open` individually.
// - `stubs.allowConcreteClassInstantiation` lets mokkery synthesize stub
//   instances for concrete constructor argument types (DeviceContext,
//   EndPlaybackSessionHandler).
mokkery {
    ignoreFinalMembers.set(true)
    stubs.allowConcreteClassInstantiation.set(true)
}

composeCompiler {
    metricsDestination = layout.buildDirectory.dir("compose-metrics")
    reportsDestination = layout.buildDirectory.dir("compose-reports")
}

compose.resources {
    // Pin the generated Res package so it stays independent of the module
    // directory name (the renamed :sharedUI module would otherwise shift it
    // to listenup.sharedui.generated.resources and break every import).
    packageOfResClass = "listenup.composeapp.generated.resources"
}

// exportLibraryDefinitions is NOT in the assemble/compile task graph — it only runs when
// explicitly invoked: ./gradlew :sharedUI:exportLibraryDefinitions
// Because regeneration is always a deliberate manual step, offlineMode = false is safe
// for CI: the export task simply never executes during a normal build.
// The collect task that DOES run during normal builds fetches nothing because
// fetchRemoteLicense = false and fetchRemoteFunding = false — so offlineMode = false has
// two independent guards and must not be "fixed" back to true.
aboutLibraries {
    offlineMode = false

    collect {
        // Disable GitHub API calls — no token required, no rate-limit risk.
        // Standard SPDX license texts are fetched from the SPDX data set without
        // needing a GitHub token; only per-repo licence discovery needs the API.
        fetchRemoteLicense = false
        fetchRemoteFunding = false
    }

    export {
        // Write the generated JSON directly into Compose resources so it is
        // bundled at compile time and accessible via Res.readBytes().
        outputFile = file("src/commonMain/composeResources/files/aboutlibraries.json")
        prettyPrint = true
    }
}

// ── OSS license-manifest drift gate ─────────────────────────────────────────
// `verifyLicenses` mirrors `verifyStrings`: regenerate, diff against the
// committed artifact, fail with a "commit the result" message on drift.
// Mechanics differ because the license data comes from the plugin's
// dependency-graph collector (not a pure function): the committed manifest is
// snapshotted BEFORE exportLibraryDefinitions rewrites it in place, then the
// gate diffs snapshot vs regenerated. On drift the corrected file is already
// on disk — review and commit it. (Export output is deterministic: entries
// are sorted and the timestamp metadata block is disabled by default.)
val licenseManifestFile = file("src/commonMain/composeResources/files/aboutlibraries.json")
val licenseManifestSnapshot = layout.buildDirectory.file("aboutLibraries/committed-manifest-snapshot.json")

val snapshotLicenseManifest =
    tasks.register("snapshotLicenseManifest") {
        description = "Snapshot the committed aboutlibraries.json before regeneration"
        // Gate plumbing: never UP-TO-DATE, must capture the pre-export content every run.
        outputs.upToDateWhen { false }
        val source = licenseManifestFile
        val target = licenseManifestSnapshot
        doLast {
            val out = target.get().asFile
            out.parentFile.mkdirs()
            out.writeText(if (source.exists()) source.readText() else "")
        }
    }

tasks.named("exportLibraryDefinitions") {
    mustRunAfter(snapshotLicenseManifest)
}

tasks.register("verifyLicenses") {
    group = "verification"
    description = "Fail if the committed aboutlibraries.json is out of sync with the dependency catalog"
    dependsOn(snapshotLicenseManifest, "exportLibraryDefinitions")
    outputs.upToDateWhen { false }
    val committed = licenseManifestSnapshot
    val regenerated = licenseManifestFile
    doLast {
        if (committed.get().asFile.readText() != regenerated.readText()) {
            throw GradleException(
                "OSS license manifest is out of sync with the dependency catalog:\n" +
                    " - sharedUI/src/commonMain/composeResources/files/aboutlibraries.json\n" +
                    "exportLibraryDefinitions has regenerated it in place — review the diff and commit the result.",
            )
        }
    }
}

kotlin {
    // Android target using new AGP 9.0-compatible plugin
    android {
        namespace = "com.calypsan.listenup.client.composeapp"

        // Enable Android resources (opt-in required with new KMP plugin)
        androidResources { enable = true }

        // Enable Android host tests (JVM-based unit tests)
        // isIncludeAndroidResources = true merges AndroidManifest + transitive AAR
        // manifests (notably ui-test-manifest's `androidx.activity.ComponentActivity`
        // launcher entry) so Robolectric-hosted Compose UI tests can resolve the
        // activity that `createComposeRule()` launches.
        withHostTest {
            isIncludeAndroidResources = true
        }

        lint {
            checkDependencies = true
        }
    }

    // JVM target for desktop (Windows/Linux)
    jvm("desktop") {
        compilerOptions {
            freeCompilerArgs.add("-Xskip-prerelease-check") // kotlinx-rpc dev-channel pin carries pre-release metadata; drop with the rpc-stable migration (libs.versions.toml)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.splashscreen)

            // Koin Android-specific
            implementation(libs.koin.android)

            // Navigation 3 Android-specific (deep linking)
            implementation(libs.androidx.navigation3.ui.android)

            // WorkManager for background sync
            implementation(libs.androidx.work.runtime.ktx)

            // Media3 for audio playback
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.session)
            implementation(libs.media3.ui)
            implementation(libs.media3.datasource.okhttp)
            implementation(libs.media3.cast)

            // Async/Future support for Media3 callbacks
            implementation(libs.concurrent.futures)

            // Window (foldable posture awareness)
            implementation(libs.androidx.window)

            // BlurHash for image placeholders

            // kotlinx-io: wrap a SAF OutputStream as a RawSink to stream backup downloads to disk
            implementation(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.androidx.graphics.shapes)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.material.icons.extended)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.compose.components.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(projects.sharedLogic)

            // Navigation 3 (multiplatform)
            implementation(libs.navigation3.ui)

            // Navigation 3 ViewModel decorator add-on (multiplatform — per-entry ViewModelStore scoping)
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)

            // Material 3 Adaptive (multiplatform)
            implementation(libs.material3.adaptive)
            implementation(libs.material3.adaptive.layout)
            implementation(libs.material3.adaptive.navigation)

            // Koin (shared across platforms)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            // Markdown Rendering (multiplatform)
            implementation(libs.markdown.renderer.m3)

            // Kotlin libraries (shared)
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // Coil for image loading (multiplatform)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)

            // AboutLibraries — open-source license loader (compose-m3 includes core + compose-core)
            implementation(libs.aboutlibraries.compose.m3)
        }
        getByName("desktopMain") {
            dependencies {
                // FFmpeg for audio decoding (self-contained, decodes all formats)
                implementation(libs.javacv)
                implementation(libs.javacpp)
                implementation(libs.ffmpeg)

                // Platform-specific native libraries
                // macOS uses AVFoundation natively (appleMain), no javacpp needed
                val javacppVersion = libs.versions.javacpp.get()
                val ffmpegVersion =
                    libs.versions.ffmpeg.javacpp
                        .get()
                listOf("linux-x86_64", "windows-x86_64").forEach { platform ->
                    implementation("org.bytedeco:javacpp:$javacppVersion:$platform")
                    implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$platform")
                }
            }
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.mokkery.runtime)

                // Kotest — canonical FunSpec framework for new androidHostTest tests.
                // The runner is the JUnit5 platform engine; junit-vintage-engine keeps
                // the pre-existing JUnit4 tests (Robolectric, Compose UI) discoverable
                // once testAndroidHostTest switches to useJUnitPlatform().
                implementation(libs.bundles.kotest)
                runtimeOnly(libs.junit.vintage.engine)

                // Phase A NavDisplay test harness
                implementation(libs.robolectric)
                implementation(libs.androidx.compose.ui.test.junit4)
                implementation(libs.androidx.compose.ui.test.manifest)
                implementation(libs.androidx.navigation3.ui.android)
                implementation(libs.androidx.lifecycle.viewmodel.navigation3)
                implementation(libs.koin.test)

                // WorkManager test helpers — TestListenableWorkerBuilder provides valid
                // WorkerParameters for worker-routing tests in ListenUpWorkerFactory.
                implementation(libs.androidx.work.testing)
            }
        }
        getByName("desktopTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.mokkery.runtime)

                // Kotest — canonical FunSpec framework for new desktopTest tests.
                // The runner is the JUnit5 platform engine; desktopTest switches to
                // useJUnitPlatform() below. No vintage engine needed here — there are
                // no JUnit4 tests in desktopTest.
                implementation(libs.bundles.kotest)
            }
        }
    }
}

// Kotest's runner is the JUnit5 platform engine, so both JVM test tasks must run on the
// JUnit Platform.
//
// testAndroidHostTest: junit-vintage-engine (added to androidHostTest) keeps the
// pre-existing JUnit4 tests — Robolectric and Compose UI — discoverable on that platform.
// The AGP KMP plugin registers testAndroidHostTest lazily, so match it by name.
//
// desktopTest: no JUnit4 tests exist here, so no vintage engine is needed.
tasks.withType<Test>().configureEach {
    if (name == "testAndroidHostTest" || name == "desktopTest") {
        useJUnitPlatform()
    }
}

// Compose UI tooling for Android preview support
dependencies {
    "androidRuntimeClasspath"(libs.compose.ui.tooling)
}

// The `generateStrings` / `verifyStrings` tasks come from the `listenup.localization` convention
// plugin. The Compose resource-processing tasks must see the generated strings.xml, so make them
// depend on generation (reference by name — the plugin registers it lazily).
tasks
    .matching {
        it.name.startsWith("generateComposeResClass") ||
            it.name.startsWith("convertXmlValueResources") ||
            it.name.startsWith("copyNonXmlValueResources") ||
            it.name.startsWith("prepareComposeResources") ||
            it.name.startsWith("generateActualResourceCollectors")
    }.configureEach {
        dependsOn("generateStrings")
        // `exportLibraryDefinitions` also writes into composeResources (aboutlibraries.json) but is kept
        // out of the normal build graph (comment above — only runs when explicitly invoked / via
        // `verifyLicenses`). Order the resource copy *after* it when both are scheduled so Gradle's
        // producer→consumer validation passes, without forcing the export into every build.
        mustRunAfter("exportLibraryDefinitions")
    }
