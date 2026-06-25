package com.calypsan.listenup.server.io

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random

private const val HEX_RADIX = 16
private const val TEMP_NAME_HEX_CHARS = 16
private const val MAX_TEMP_FILE_ATTEMPTS = 10_000

/** Deletes [path] and everything under it (post-order: children before parents). No-op if absent. */
internal fun deleteRecursively(path: Path) {
    if (!SystemFileSystem.exists(path)) return
    val meta = SystemFileSystem.metadataOrNull(path)
    if (meta?.isDirectory == true) {
        for (child in SystemFileSystem.list(path)) deleteRecursively(child)
    }
    SystemFileSystem.delete(path, mustExist = false)
}

/** Creates a uniquely-named empty file inside [dir] (which must exist) and returns its path. */
internal fun createTempFileIn(
    dir: Path,
    prefix: String,
    suffix: String,
): Path {
    SystemFileSystem.createDirectories(dir)
    repeat(MAX_TEMP_FILE_ATTEMPTS) {
        val name = "$prefix${randomHex()}$suffix"
        val candidate = Path(dir, name)
        if (!SystemFileSystem.exists(candidate)) {
            SystemFileSystem.sink(candidate).close() // create empty
            return candidate
        }
    }
    error("could not allocate a temp file in $dir")
}

/** [this] relative to [base] as a string, or [this] as-is if it's not under [base]. */
internal fun Path.relativeTo(base: Path): String {
    val p = this.toString()
    val b = base.toString().trimEnd('/')
    return if (p.startsWith("$b/")) p.removePrefix("$b/") else p
}

/** True if [this] is [base] itself or nested under it (string-prefix on normalized paths). */
internal fun Path.isUnder(base: Path): Boolean {
    val p = this.toString()
    val b = base.toString().trimEnd('/')
    return p == b || p.startsWith("$b/")
}

private fun randomHex(): String =
    (0 until TEMP_NAME_HEX_CHARS).joinToString("") { Random.nextInt(HEX_RADIX).toString(HEX_RADIX) }
