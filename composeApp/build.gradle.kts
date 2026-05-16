import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.mokkery)
}

// Mokkery is used in desktopTest only — see composeApp/src/desktopTest.
//
// Scope note: this configuration currently exists for a single test file —
// DesktopPlaybackControllerTest — which constructs a never-invoked
// `mock<PlaybackManager>()` to satisfy the controller's constructor. Both flags
// below are module-wide and therefore apply to every future mokkery mock in
// composeApp/desktopTest; revisit them when a second test surfaces a different
// mockability need (e.g. a real test that exercises PlaybackManager behaviour,
// at which point a hand-rolled FakePlaybackManager + an interface seam is the
// likely better answer).
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

kotlin {
    // Android target using new AGP 9.0-compatible plugin
    androidLibrary {
        namespace = "com.calypsan.listenup.client.composeapp"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

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
            checkDependencies = true
            htmlReport = true
            xmlReport = true
        }
    }

    // JVM target for desktop (Windows/Linux)
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-Xexpect-actual-classes",
                "-Xreturn-value-checker=check",
                "-Xexplicit-backing-fields",
                "-Xskip-prerelease-check", // Skip pre-release Kotlin metadata check
            )
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

            // Navigation 3 ViewModel decorator add-on (per-entry ViewModelStore scoping)
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)

            // WorkManager for background sync
            implementation(libs.androidx.work.runtime.ktx)

            // Material Icons Extended
            implementation(libs.androidx.material.icons.extended)

            // Media3 for audio playback
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.session)
            implementation(libs.media3.ui)
            implementation(libs.media3.datasource.okhttp)

            // Async/Future support for Media3 callbacks
            implementation(libs.concurrent.futures)

            // Palette for dynamic color extraction from cover art
            implementation(libs.androidx.palette.ktx)

            // Window (foldable posture awareness)
            implementation(libs.androidx.window)

            // BlurHash for image placeholders
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.material.icons.extended)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(projects.shared)

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
            implementation(libs.kotlinx.collections.immutable)

            // Coil for image loading (multiplatform)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)

            // KMPalette for cross-platform color extraction
            implementation(libs.kmpalette.core)
        }
        val desktopMain by getting {
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
        val androidHostTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)

                // Phase A NavDisplay test harness
                implementation(libs.robolectric)
                implementation(libs.androidx.compose.ui.test.junit4)
                implementation(libs.androidx.compose.ui.test.manifest)
                implementation(libs.androidx.navigation3.ui.android)
                implementation(libs.androidx.lifecycle.viewmodel.navigation3)
                implementation(libs.koin.test)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.mokkery.runtime)
            }
        }
    }
}

// Compose UI tooling for Android preview support
dependencies {
    "androidRuntimeClasspath"(libs.compose.ui.tooling)
}

// --- Localization: generate platform string files from shared JSON ---
val generateStrings by tasks.registering {
    group = "localization"
    description = "Generate platform string resource files from shared JSON"

    val stringsDir = rootProject.file("shared/src/commonMain/resources/strings")
    val outputDir = project.file("src/commonMain/composeResources")

    inputs.dir(stringsDir)
    // Don't declare outputs.dir on composeResources — it conflicts with Compose plugin tasks.
    // Instead we just generate files in place and let Compose pick them up.

    doLast {
        stringsDir.listFiles { f -> f.extension == "json" }?.forEach { jsonFile ->
            val locale = jsonFile.nameWithoutExtension
            val valuesFolder = if (locale == "en") "values" else "values-" + locale
            val outDir = outputDir.resolve(valuesFolder)
            outDir.mkdirs()

            @Suppress("UNCHECKED_CAST")
            val map = groovy.json.JsonSlurper().parseText(jsonFile.readText()) as Map<String, Any>

            val entries = mutableListOf<Pair<String, String>>()

            fun flatten(
                prefix: String,
                obj: Any,
            ) {
                when (obj) {
                    is Map<*, *> -> {
                        obj.forEach { (k, v) ->
                            flatten(if (prefix.isEmpty()) k.toString() else prefix + "_" + k, v!!)
                        }
                    }

                    else -> {
                        entries.add(prefix to obj.toString())
                    }
                }
            }
            flatten("", map)

            val sb = StringBuilder()
            sb.appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            sb.appendLine("<resources>")
            entries.sortedBy { it.first }.forEach { (key, value) ->
                val escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'")
                sb.appendLine("    <string name=\"" + key + "\">" + escaped + "</string>")
            }
            sb.appendLine("</resources>")
            outDir.resolve("strings.xml").writeText(sb.toString())
            println("Generated " + valuesFolder + "/strings.xml (" + entries.size + " strings)")
        }
    }
}

tasks
    .matching {
        it.name.startsWith("generateComposeResClass") ||
            it.name.startsWith("convertXmlValueResources") ||
            it.name.startsWith("copyNonXmlValueResources") ||
            it.name.startsWith("prepareComposeResources") ||
            it.name.startsWith("generateActualResourceCollectors")
    }.configureEach {
        dependsOn(generateStrings)
    }
