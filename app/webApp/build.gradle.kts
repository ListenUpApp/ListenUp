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
