plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // Compose HTML — real DOM, not the canvas renderer. `:app:sharedUI` is Compose
    // Multiplatform and does NOT transfer here: different toolkit, different primitives. The web
    // body is built natively against the DOM, per the platform direction.
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // ksp is here only because the kotest plugin requires it — Kotest 6 replaced its
    // compiler plugin with a KSP processor for non-JVM spec discovery. Declared before
    // kotest, same order as :server.
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotest)
    // The serialization RUNTIME was already here; the compiler plugin was not, because until
    // now this module only ever consumed serializers generated in `:contract`. The licence
    // manifest is this module's own `@Serializable` type, and without the plugin `@Serializable`
    // is inert: it compiles, then throws at runtime looking for a serializer nobody generated.
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.aboutlibraries)
}

// ── OSS licence manifest (web) ──────────────────────────────────────────────
// ⛔ A separate manifest from `:app:sharedUI`'s, deliberately. That one is the ANDROID dependency
// graph — Media3, Firebase, Play Services, bytedeco — and the browser loads none of it. Shipping it
// here would attribute libraries this client never bundles, which on an attribution page is worse
// than having no page at all.
//
// This collects THIS module's graph, which is the Kotlin/JS half of what the browser runs. The
// other half is npm (see `web/scripts/collect-npm-licences.mjs`); `mergeWebLicences` joins them.
aboutLibraries {
    offlineMode = false

    collect {
        // Same two guards :app:sharedUI sets, for the same reason: no GitHub API calls, so no token
        // and no rate limit. SPDX texts come from the SPDX data set.
        fetchRemoteLicense = false
        fetchRemoteFunding = false
    }

    export {
        outputFile = file("build/aboutLibraries/kotlin-licences.json")
        prettyPrint = true
    }
}

// The merged manifest the browser fetches. Committed rather than generated at build time, for the
// reason `:app:sharedUI` commits its own: it is an artifact a human should review when it changes,
// and `verifyWebLicences` is the gate that makes a silent change impossible.
//
// The merge itself lives in Node rather than here: both inputs are JSON, one of them comes from
// `pnpm`, and Gradle's Kotlin DSL has no JSON parser without pulling a dependency into the build.
val webLicenceManifest = file("web/public/licences.json")

// ⛔ Snapshot BEFORE the merge overwrites it, as its own task — the same shape and the same reason
// as `:app:sharedUI`'s `snapshotLicenseManifest`. Taken in the verify task's `doFirst` instead, it
// would run after its own `dependsOn` had already rewritten the file, and the gate would compare
// the regenerated manifest against itself and pass forever.
val snapshotWebLicences =
    tasks.register("snapshotWebLicences") {
        description = "Snapshot the committed web licence manifest before regeneration"
        outputs.upToDateWhen { false }
        val source = webLicenceManifest
        val target = layout.buildDirectory.file("aboutLibraries/committed-web-manifest.json")
        doLast {
            val out = target.get().asFile
            out.parentFile.mkdirs()
            out.writeText(if (source.exists()) source.readText() else "")
        }
    }

val mergeWebLicences =
    tasks.register<Exec>("mergeWebLicences") {
        group = "build"
        description = "Merge the Kotlin/JS and npm licence manifests into web/public/licences.json"
        dependsOn("exportLibraryDefinitions")
        mustRunAfter(snapshotWebLicences)
        // Both halves are collectors over a dependency graph, not pure functions of tracked files.
        outputs.upToDateWhen { false }
        workingDir = file("web")
        commandLine(
            "node",
            "scripts/build-licences.mjs",
            layout.buildDirectory
                .file("aboutLibraries/kotlin-licences.json")
                .get()
                .asFile.absolutePath,
            webLicenceManifest.absolutePath,
        )
    }

tasks.register("verifyWebLicences") {
    group = "verification"
    description = "Fail if web/public/licences.json is out of sync with the Kotlin/JS and npm graphs"
    dependsOn(snapshotWebLicences, mergeWebLicences)
    outputs.upToDateWhen { false }
    val output = webLicenceManifest
    val snapshot = layout.buildDirectory.file("aboutLibraries/committed-web-manifest.json")
    doLast {
        if (snapshot.get().asFile.readText() != output.readText()) {
            throw GradleException(
                "web/public/licences.json is out of date with the dependency graph. The corrected " +
                    "file is already on disk — review and commit it.",
            )
        }
    }
}

kotlin {
    js {
        // Emit ES modules rather than a UMD/CommonJS bundle. This is the hinge of the toolchain
        // decoupling: KGP stops owning the bundler and simply hands a standard ESM artifact to
        // the Vite project in `web/`, which owns dev server, build and tests from there.
        useEsModules()
        browser()
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.html.core)
            implementation(projects.app.sharedLogic)
            // The web application starts Koin and holds the Book Detail ViewModel's lifetime
            // itself (a browser has no ViewModelStore), so it names both directly — :app:
            // sharedLogic keeps them `implementation` and they don't arrive transitively.
            implementation(libs.koin.core)
            implementation(libs.androidx.lifecycle.viewmodel)
            // Same reason as the two above: :contract's DTOs are @Serializable, so naming one of
            // their enum constants (Admin's registration policy) needs the serialization runtime on
            // this module's own compile classpath — the generated companion's supertype lives there.
            implementation(libs.kotlinx.serialization.json)
            // No npm dependency declarations here on purpose. The bare specifiers this emits —
            // `hls.js`, and the SQLite worker's `sqlite-wasm-worker/worker.js` — are resolved by
            // the bundler in `web/`, out of web/node_modules and the copy sync-kotlin.mjs makes.
            // KGP owns the compiler, not the module graph, so it declares neither.
            // Backups are the one feature whose shared seams are IO types rather than domain types:
            // the web module implements `FileSource` (ktor's ByteReadChannel) for an upload and a
            // `RawSink` (kotlinx.io) for a download, so both have to be on this module's own
            // classpath — :app:sharedLogic keeps them `implementation` and they don't arrive
            // transitively. Carried over from main; the npm declarations that sat beside them are
            // deliberately NOT carried over, since the bundler in web/ resolves those specifiers now.
            implementation(libs.kotlinx.io.core)
            implementation(libs.ktor.io)
        }
        jsTest.dependencies {
            implementation(libs.kotest.framework.engine)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
            // :app:sharedLogic depends on kotlinx-datetime as `implementation`, so it doesn't
            // transit here — declared directly for TimeZoneOnJsTest.
            implementation(libs.kotlinx.datetime)
        }
    }
}

// =============================================================================
// THE VITE/PLAYWRIGHT BROWSER LANE
// =============================================================================
// Runs the compiled Kotest bundle in Chromium via `app/webApp/web`, where Vite and Playwright
// own the browser instead of KGP.
//
// This is the only browser lane: it replaced jsBrowserTest, which is gone along with the karma
// and webpack configuration it needed. CI runs both halves of it — `webKotest` (ci.yml, the
// test-web-browser job) and `webAuthKotest` (test-web-browser-server).
//
// Not wired into `check` — it needs pnpm and a Playwright browser download, which is a bigger
// ask of a contributor's machine than the rest of the build makes.
val webRoot = layout.projectDirectory.dir("web")

val pnpmInstall =
    tasks.register<Exec>("webPnpmInstall") {
        group = "verification"
        description = "Installs the JS toolchain for the Vite browser lane."
        workingDir = webRoot.asFile
        commandLine("pnpm", "install", "--frozen-lockfile")
        inputs.file(webRoot.file("package.json"))
        inputs.file(webRoot.file("pnpm-lock.yaml"))
        outputs.dir(webRoot.dir("node_modules"))
    }

tasks.register<Exec>("webKotest") {
    group = "verification"
    description = "Runs the Kotest specs in Chromium via Vite + Playwright."
    dependsOn(pnpmInstall, "jsTestTestDevelopmentExecutableCompileSync")
    workingDir = webRoot.asFile
    commandLine("pnpm", "test")
    // The Kotlin output is an input in substance — `pnpm test` syncs it into web/kotlin — so
    // declaring it keeps Gradle from calling this up to date after a Kotlin-only change.
    inputs.dir(layout.buildDirectory.dir("compileSync/js/test/testDevelopmentExecutable/kotlin"))
    outputs.upToDateWhen { false }
}

tasks.register<Exec>("webAuthKotest") {
    group = "verification"
    description = "Runs the browser specs against a REAL server (transport + auth proofs)."
    dependsOn(pnpmInstall, "jsTestTestDevelopmentExecutableCompileSync")
    workingDir = webRoot.asFile
    commandLine("pnpm", "test:auth")
    outputs.upToDateWhen { false }
}
