package com.calypsan.listenup.gradle

import java.io.File

/**
 * Gradle-task-side glue around [LocalizationGenerator]: discovers the locale JSON files and maps
 * every output artifact to its rendered content.
 *
 * Kept as a standalone object (not a precompiled-script-plugin function) so the `generateStrings` /
 * `verifyStrings` task actions reference only this object plus captured [File]s — never the script's
 * `Project`. That keeps the tasks compatible with Gradle's configuration cache.
 */
object LocalizationArtifacts {
    /**
     * A SHA-256 of the compiled renderer ([LocalizationGenerator] and this object), for declaring as a
     * task input: Gradle's up-to-date check only sees the JSON sources, so without it a change to the
     * generator itself leaves `generateStrings` UP-TO-DATE and the committed artifacts stale.
     */
    fun implementationFingerprint(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for (type in listOf(LocalizationGenerator::class.java, LocalizationArtifacts::class.java)) {
            val bytes =
                checkNotNull(type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")) {
                    "no class file for ${type.name}"
                }.use { it.readBytes() }
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Renders the full set of localization artifacts for the current source state.
     *
     * @param stringsDir the directory holding the per-locale `<code>.json` source files.
     * @param composeResourcesDir the parent dir for Android `values[-xx]/strings.xml` outputs.
     * @param xcstringsOut the iOS String Catalog output file.
     * @return each output [File] mapped to the exact content it should contain.
     */
    fun render(
        stringsDir: File,
        composeResourcesDir: File,
        xcstringsOut: File,
    ): Map<File, String> {
        val byCode: Map<String, Map<String, String>> =
            (stringsDir.listFiles { file -> file.extension == "json" } ?: emptyArray())
                .associate { it.nameWithoutExtension to LocalizationGenerator.parse(it.readText()) }
        return buildMap {
            // Android: one strings.xml per locale (values / values-xx).
            byCode.forEach { (code, strings) ->
                val folder = if (code == "en") "values" else "values-$code"
                put(
                    File(File(composeResourcesDir, folder), "strings.xml"),
                    LocalizationGenerator.androidXml(strings),
                )
            }
            // iOS: a single String Catalog holding every locale.
            put(xcstringsOut, LocalizationGenerator.xcstrings(byCode, sourceLanguage = "en"))
        }
    }

    /**
     * The artifacts in [rendered] whose [baseline] content does not match what the JSON source renders.
     *
     * @param rendered the output of [render] — each artifact mapped to the content it should contain.
     * @param baseline the content to compare against, or null when the artifact has no baseline at all
     *   (never committed) — which counts as drift, so a brand-new artifact is reported rather than skipped.
     * @return the drifting files, in stable path order.
     */
    fun drift(
        rendered: Map<File, String>,
        baseline: (File) -> String?,
    ): List<File> =
        rendered
            .filter { (file, content) -> baseline(file) != content }
            .keys
            .sortedBy { it.invariantSeparatorsPath }
}
