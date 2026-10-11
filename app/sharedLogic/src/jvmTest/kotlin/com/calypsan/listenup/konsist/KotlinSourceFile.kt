package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import java.io.File

/** A Kotlin source file found by [walkKotlinSources]: its repo-relative path and raw bytes. */
internal class KotlinSourceFile(
    val relativePath: String,
    val bytes: ByteArray,
) {
    /** The bytes decoded leniently as UTF-8, for text matchers that must not throw on bad input. */
    val text: String get() = bytes.toString(Charsets.UTF_8)
}

/**
 * Every `*.kt` and `*.kts` file under each real module's `src/` directory, across all source
 * sets (tests included), read as raw bytes.
 *
 * Scoped by [discoverModuleSrcDirs] for the same reason [productionScope] is: the repo root may
 * hold full second copies of the tree under hidden worktree directories.
 */
internal fun walkKotlinSources(root: File = File(Konsist.projectRootPath)): List<KotlinSourceFile> =
    discoverModuleSrcDirs(root).flatMap { srcDir ->
        File(root, srcDir)
            .walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile && (it.extension == "kt" || it.extension == "kts") }
            .map { KotlinSourceFile(it.relativeTo(root).invariantSeparatorsPath, it.readBytes()) }
            .toList()
    }
