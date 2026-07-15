package com.calypsan.listenup.server.io

import kotlinx.io.buffered
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

/**
 * [this] relative to [base] as a string, or `null` when [this] is not under [base].
 *
 * Returning `null` for a non-descendant (rather than the absolute path as-is) forces callers to
 * decide what a non-descendant means for them — the silent absolute-path fallback was the footgun
 * behind a prior absolute-`rootRelPath` regression, where a mismatched base leaked absolute paths
 * downstream instead of failing visibly.
 */
internal fun Path.relativeTo(base: Path): String? {
    val p = this.toString()
    val b = base.toString().trimEnd('/')
    return when {
        p == b -> ""
        p.startsWith("$b/") -> p.removePrefix("$b/")
        else -> null
    }
}

/** True if [this] is [base] itself or nested under it (string-prefix on normalized paths). */
internal fun Path.isUnder(base: Path): Boolean {
    val p = this.toString()
    val b = base.toString().trimEnd('/')
    return p == b || p.startsWith("$b/")
}

private fun randomHex(): String =
    (0 until TEMP_NAME_HEX_CHARS).joinToString("") { Random.nextInt(HEX_RADIX).toString(HEX_RADIX) }

/**
 * Every regular file under [dir] (recursively), sorted by full path string — reproducing
 * `java.nio.file.Files.walk(dir).filter(isRegularFile).sorted()`. Returns empty if [dir] is absent.
 *
 * The full-path sort (not a per-level name sort) is load-bearing: the backup image-prefix checksum
 * is a rolling digest over these files in this exact order, and old archives were written in it.
 */
internal fun listRegularFilesRecursively(dir: Path): List<Path> {
    if (!SystemFileSystem.exists(dir)) return emptyList()
    val out = mutableListOf<Path>()

    fun recurse(d: Path) {
        for (child in SystemFileSystem.list(d)) {
            if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) {
                recurse(child)
            } else {
                out.add(child)
            }
        }
    }
    recurse(dir)
    return out.sortedBy { it.toString() }
}

/** Recursively copies the tree at [src] into [dst] (created if absent). No-op if [src] is absent. */
internal fun copyDirectoryRecursively(
    src: Path,
    dst: Path,
) {
    if (!SystemFileSystem.exists(src)) return
    SystemFileSystem.createDirectories(dst)
    for (child in SystemFileSystem.list(src)) {
        val target = Path(dst, child.name)
        if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) {
            copyDirectoryRecursively(child, target)
        } else {
            SystemFileSystem.source(child).use { input ->
                SystemFileSystem.sink(target).buffered().use { it.transferFrom(input) }
            }
        }
    }
}
