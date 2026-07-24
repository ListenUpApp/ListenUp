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
}
