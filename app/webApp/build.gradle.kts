plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // ksp is here only because the kotest plugin requires it — Kotest 6 replaced its
    // compiler plugin with a KSP processor for non-JVM spec discovery. Declared before
    // kotest, same order as :server.
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotest)
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "listenup.js"
            }
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
            implementation(projects.app.sharedLogic)
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
