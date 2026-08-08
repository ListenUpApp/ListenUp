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
                // karma-chrome-launcher resolves the browser binary from CHROME_BIN. Honour
                // whatever the environment already provides (CI images set this themselves,
                // e.g. browser-actions/setup-chrome) and fall back to the Chromium binary this
                // dev machine actually has — there is no google-chrome here, only chromium.
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
        }
        jsTest.dependencies {
            implementation(libs.kotest.framework.engine)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
