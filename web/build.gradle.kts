import com.calypsan.listenup.gradle.TailwindGenerateTask
import com.calypsan.listenup.gradle.TailwindResolveCliTask

plugins {
    id("listenup.jvm")
    alias(libs.plugins.kotlinSerialization)
}

group = "com.calypsan.listenup"
version = "0.0.1"

dependencies {
    implementation(projects.contract)

    // Ktor server surface used by the embedded web routes (mounted by :server).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.csrf)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.kotlinx.html)

    // Loopback REST client (used in Phase 1B; declared now so the module is self-contained).
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Test
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.konsist)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    compilerOptions {
        // :contract is compiled by a pre-release Kotlin; allow consuming its classfiles.
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

tasks.test {
    useJUnitPlatform()
}

// Tailwind: scan :web .kt files for class names and emit one stylesheet at
// classpath `web/app.css` (served at /assets/app.css). Uses the Tailwind v3 standalone
// CLI (no npm/Node). Binary resolution: TAILWIND_CLI env -> a pinned linux-x64 binary
// auto-downloaded into build/tailwind/ -> `tailwindcss` on PATH. Non-linux devs: set TAILWIND_CLI.
val tailwindVersion = "3.4.17"
val tailwindGenRoot = layout.buildDirectory.dir("generated-resources/tailwind")
val tailwindBinFile = layout.buildDirectory.file("tailwind/tailwindcss")

val tailwindResolveCli by tasks.registering(TailwindResolveCliTask::class) {
    description = "Ensure a Tailwind standalone CLI binary is available."
    outputBin.set(tailwindBinFile)
    version.set(tailwindVersion)
    // Official SHA-256 of tailwindcss-linux-x64 v3.4.17, from the release's sha256sums.txt:
    // https://github.com/tailwindlabs/tailwindcss/releases/download/v3.4.17/sha256sums.txt
    expectedSha256.set("7d24f7fa191d2193b78cd5f5a42a6093e14409521908529f42d80b11fde1f1d4")
    tailwindCliEnv.set(providers.environmentVariable("TAILWIND_CLI"))
}

val tailwindGenerate by tasks.registering(TailwindGenerateTask::class) {
    dependsOn(tailwindResolveCli)
    group = "web"
    description = "Generate the Tailwind stylesheet for the web UI."
    sourceDir.set(layout.projectDirectory.dir("src/main/kotlin"))
    configFile.set(layout.projectDirectory.file("tailwind.config.js"))
    inputCss.set(layout.projectDirectory.file("src/main/tailwind/input.css"))
    outputCss.set(tailwindGenRoot.map { it.file("web/app.css") })
    downloadedBin.set(tailwindBinFile)
    tailwindCliEnv.set(providers.environmentVariable("TAILWIND_CLI"))
}

sourceSets.main {
    resources.srcDir(tailwindGenRoot)
}

tasks.named("processResources") {
    dependsOn(tailwindGenerate)
}
