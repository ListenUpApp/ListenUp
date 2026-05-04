package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.server.scanner.inference.FileTypeRules
import com.calypsan.listenup.server.scanner.inference.SkipRules
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

private val logger = KotlinLogging.logger {}

/**
 * Stage 1 of the scanner pipeline: enumerates every file beneath a library
 * root and emits one [FileEntry] per file the [SkipRules] don't filter out.
 *
 * Implementation notes:
 *  - Built on `Files.walkFileTree` (NIO) — gives us subtree pruning for
 *    `@eaDir` directories and `.ignore`-flagged subtrees, which `Files.walk`
 *    can't express cleanly.
 *  - Symlinks are **not** followed. This avoids cycles and accidental
 *    enumeration of files outside the library root. The cost: a symlinked
 *    audio collection isn't visible. Worth it for safety; users wanting
 *    symlink support can request it later.
 *  - Inode capture uses `BasicFileAttributes.fileKey()`. On POSIX this is a
 *    `(dev, ino)` pair; we extract the ino half for the [FileEntry.inode]
 *    field. On Windows default filesystems `fileKey()` returns null and
 *    cross-folder move detection in the Differ degrades to name-only.
 *  - Paths are emitted with POSIX separators in [FileEntry.relPath]
 *    regardless of OS, so the wire format stays uniform.
 *  - Walk is collected into a list before emission. Library sizes are bounded
 *    (thousands of files, not gigabytes streamed); the simplicity beats the
 *    backpressure precision of channelFlow for this use case.
 */
class Walker(
    private val skipRules: (Path) -> Boolean = SkipRules::shouldSkip,
) {
    fun walk(root: Path): Flow<FileEntry> =
        flow {
            if (!Files.isDirectory(root)) return@flow

            val collected = collectEntries(root)
            collected.forEach { emit(it) }
        }.flowOn(Dispatchers.IO)

    private fun collectEntries(root: Path): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        Files.walkFileTree(
            root,
            // Empty option set → do NOT follow symlinks. Cycles in symlinked
            // libraries can't recurse into themselves.
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult =
                    if (dir != root && skipRules(dir)) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (skipRules(file)) return FileVisitResult.CONTINUE
                    entries += toEntry(root, file, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    // Per the "never stranded" principle: log and continue.
                    // A single unreadable file shouldn't fail the whole scan.
                    logger.warn(exc) { "walk: failed to read $file — skipping" }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return entries
    }

    private fun toEntry(
        root: Path,
        file: Path,
        attrs: BasicFileAttributes,
    ): FileEntry {
        val name = file.fileName.toString()
        val relPath = root.relativize(file).toString().replace('\\', '/')
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return FileEntry(
            relPath = relPath,
            name = name,
            ext = ext,
            size = attrs.size(),
            mtimeMs = attrs.lastModifiedTime().toMillis(),
            inode = extractInode(attrs.fileKey()),
            fileType = FileTypeRules.classify(name),
        )
    }
}

// `UnixFileKey.toString()` has the format `(dev=N,ino=M)` since OpenJDK 8.
// We only need the inode half — equality on `(dev, ino)` is what the Differ
// uses, but two files in the same library always live on the same device,
// so just the ino is enough to detect moves within a library.
private val INODE_PATTERN = Regex("""ino=(\d+)""")

private fun extractInode(fileKey: Any?): Long? =
    fileKey?.toString()?.let {
        INODE_PATTERN
            .find(it)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
    }
