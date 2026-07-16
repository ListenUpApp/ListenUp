import com.calypsan.listenup.gradle.LocalizationArtifacts
import com.calypsan.listenup.gradle.LocalizationGenerator
import com.calypsan.listenup.gradle.SwiftStringKeys
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

    // Declares only inputs, no outputs: this is a gate that must always re-verify
    // (never report UP-TO-DATE). It is pure in-memory I/O, so re-running is cheap.
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

// The Swift side of the localization contract. `verifyStrings` above proves the generated
// artifacts match `en.json`; it cannot see which keys Swift actually asks for. iOS resolves a
// missing key by rendering the key itself, so a typo'd or deleted key ships as visible garbage
// ("admin.add_this_folder" rendered to admins) with nothing red anywhere — and Xcode hides it
// locally by scraping Swift and writing the missing key back into the generated catalog as an
// empty entry. This gate is the missing half: every statically-resolvable
// `String(localized: "…")` key must exist in en.json.
val iosSwiftDir: File = rootProject.file("iosApp/ListenUp")

tasks.register("verifySwiftStringKeys") {
    group = "localization"
    description = "Fail if iOS Swift references a localization key that does not exist in en.json"

    // Inputs only, no outputs — a gate that must always re-verify, never report UP-TO-DATE.
    inputs.dir(stringsDir)
    inputs.dir(iosSwiftDir)

    val enJson = File(stringsDir, "en.json")
    val swiftDir = iosSwiftDir
    val projectRoot = rootDir

    doLast {
        val knownKeys = LocalizationGenerator.parse(enJson.readText()).keys
        check(knownKeys.isNotEmpty()) {
            "No keys parsed from ${enJson.relativeTo(projectRoot)} — the gate would pass vacuously."
        }

        val swiftSources =
            swiftDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "swift" }
                .associate { it.relativeTo(projectRoot).path to it.readText() }
        check(swiftSources.isNotEmpty()) {
            "No .swift files found under ${swiftDir.relativeTo(projectRoot)} — the gate would pass vacuously."
        }

        val missing = SwiftStringKeys.missingKeys(swiftSources, knownKeys)
        if (missing.isNotEmpty()) {
            val offenders =
                missing.entries.joinToString("\n") { (key, files) ->
                    " - \"$key\" referenced by ${files.joinToString(", ")}"
                }
            throw GradleException(
                "iOS Swift references localization key(s) missing from en.json:\n" +
                    offenders +
                    "\niOS renders a missing key as its own text, so these ship as visible garbage. " +
                    "Add the key to en.json (then ./gradlew :sharedUI:generateStrings) or fix the reference.",
            )
        }
    }
}
