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
        // Emit ES modules rather than the webpack-oriented UMD/CommonJS bundle. This is the
        // hinge of the toolchain decoupling: KGP stops owning the bundler and simply hands a
        // standard ESM artifact to the Vite project in `web/`, which owns dev server, build
        // and tests from there. Note useEsModules() and webpack do not coexist
        // (JetBrains/compose-multiplatform#3724) — that is intended, webpack is what we are
        // removing.
        useEsModules()
        browser {
            testTask {
                // karma-chrome-launcher resolves the browser binary from CHROME_BIN, and its
                // own auto-detect searches only for google-chrome/google-chrome-stable on
                // Linux — never chromium. So honour whatever the environment already provides
                // (CI images export it themselves, e.g. browser-actions/setup-chrome) and
                // otherwise fall back to a Chromium binary, which is what many Linux dev boxes
                // and slim CI images ship instead of Chrome.
                //
                // Deliberately NOT useChromiumHeadless(): that DSL reads CHROMIUM_BIN and never
                // CHROME_BIN, so it would look simpler and silently break CI, where Chrome is
                // installed and CHROME_BIN is the variable that gets set.
                //
                // If the lane fails locally with a browser-not-found error on a distro that
                // packages chromium elsewhere (Flatpak, Nix, Snap), export CHROME_BIN yourself.
                val chromeBin =
                    System.getenv("CHROME_BIN")
                        ?: listOf("/usr/bin/chromium", "/usr/bin/chromium-browser")
                            .firstOrNull { file(it).exists() }
                if (chromeBin != null) {
                    environment("CHROME_BIN", chromeBin)
                }
                useKarma {
                    useChromeHeadless()
                }
            }
        }
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
            // The sqlite-web driver ships NO worker script — it only speaks a documented
            // message protocol (see WebWorkerSQLiteDriver's KDoc). The worker is a local npm
            // module we supply (webApp/worker), wrapping @sqlite.org/sqlite-wasm; webpack
            // resolves `new URL("sqlite-wasm-worker/worker.js", import.meta.url)` into a
            // separate worker chunk. Pattern from danysantiago/room-web-demo.
            implementation(npm("sqlite-wasm-worker", layout.projectDirectory.dir("worker").asFile))
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
// own the browser instead of KGP's webpack + karma.
//
// It runs ALONGSIDE jsBrowserTest rather than replacing it: both lanes execute the same specs,
// so while the migration is in flight a disagreement between them is a signal, not a puzzle.
// jsBrowserTest goes when this lane has earned CI's trust.
//
// Not wired into `check` yet — it needs pnpm and a Playwright browser download, which is a
// bigger ask of a contributor's machine than the rest of the build makes.
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
    description = "Runs the Kotest specs in Chromium via Vite + Playwright (the post-karma lane)."
    dependsOn(pnpmInstall, "jsTestTestDevelopmentExecutableCompileSync")
    workingDir = webRoot.asFile
    commandLine("pnpm", "test")
    // The Kotlin output is an input in substance — `pnpm test` syncs it into web/kotlin — so
    // declaring it keeps Gradle from calling this up to date after a Kotlin-only change.
    inputs.dir(layout.buildDirectory.dir("compileSync/js/test/testDevelopmentExecutable/kotlin"))
    outputs.upToDateWhen { false }
}
