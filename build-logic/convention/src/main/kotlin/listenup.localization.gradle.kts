import com.calypsan.listenup.gradle.LocalizationArtifacts
import java.io.File

// --- Localization: generate native string resources from the shared JSON source of truth ---
//
// The pure, unit-tested rendering logic lives in `LocalizationGenerator`; `LocalizationArtifacts`
// wraps it with the file-discovery glue. Both are real classes in this build-logic subproject (on
// the precompiled-script-plugin classpath), so the task actions reference only those objects plus
// captured `File`s — never the script's `Project`, keeping the tasks configuration-cache compatible.
//
// These tasks are the thin Gradle shell: declare inputs/outputs, then render + write.
//  - Android `strings.xml` (snake_case keys) is generated per-locale into the applying project's
//    `src/commonMain/composeResources/values[-xx]/`, alongside the hand-authored `drawable/` etc.,
//    so the Compose plugin picks it up via its standard convention and the `Res` class still sees
//    every string. Unlike a Compose `customDirectory` (which *replaces* the convention path and
//    would drop the sibling drawables), in-place generation is additive. The build #4 issue — the
//    old task declared no outputs — is fixed by declaring `inputs.dir(stringsDir)` plus the
//    per-locale `values[-xx]` output dirs (narrower than the whole composeResources dir, so it
//    doesn't fight the Compose plugin's claim on the parent).
//  - The iOS String Catalog (`Localizable.xcstrings`, dotted keys, all locales) is a committed
//    artifact consumed by Xcode.

val stringsDir: File = rootProject.file("sharedLogic/src/commonMain/resources/strings")
val composeResourcesDir: File = layout.projectDirectory.dir("src/commonMain/composeResources").asFile
val xcstringsOut: File = rootProject.file("iosApp/ListenUp/Resources/Localizable.xcstrings")
val rootDir: File = rootProject.projectDir

// The per-locale Android output dirs (values, values-xx, …), derived from the source JSON filenames,
// so Gradle can track them as declared outputs without claiming the whole composeResources dir.
fun androidValueDirs(): List<File> =
    (stringsDir.listFiles { file -> file.extension == "json" } ?: emptyArray())
        .map { json ->
            val locale = json.nameWithoutExtension
            File(composeResourcesDir, if (locale == "en") "values" else "values-$locale")
        }

tasks.register("generateStrings") {
    group = "localization"
    description = "Generate native string resources (Android strings.xml + iOS Localizable.xcstrings) from shared JSON"

    inputs.dir(stringsDir)
    outputs.files(androidValueDirs().map { File(it, "strings.xml") })
    outputs.file(xcstringsOut)

    val sourceDir = stringsDir
    val androidParent = composeResourcesDir
    val catalogFile = xcstringsOut

    doLast {
        LocalizationArtifacts
            .render(sourceDir, androidParent, catalogFile)
            .forEach { (file, content) ->
                file.parentFile.mkdirs()
                file.writeText(content)
            }
    }
}

tasks.register("verifyStrings") {
    group = "localization"
    description = "Fail if the committed string artifacts are out of sync with the shared JSON source"

    inputs.dir(stringsDir)

    val sourceDir = stringsDir
    val androidParent = composeResourcesDir
    val catalogFile = xcstringsOut
    val projectRoot = rootDir

    doLast {
        val drift =
            LocalizationArtifacts
                .render(sourceDir, androidParent, catalogFile)
                .filter { (file, content) -> !file.exists() || file.readText() != content }
        if (drift.isNotEmpty()) {
            val offenders = drift.keys.joinToString("\n") { " - ${it.relativeTo(projectRoot)}" }
            throw GradleException(
                "Localization artifacts are out of sync with the shared JSON source:\n" +
                    offenders +
                    "\nRun ./gradlew :sharedUI:generateStrings and commit the result.",
            )
        }
    }
}
