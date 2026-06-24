package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.isSymlink
import com.calypsan.listenup.server.io.relativeTo
import com.calypsan.listenup.server.io.statFile
import com.calypsan.listenup.server.scanner.inference.FileTypeRules
import com.calypsan.listenup.server.scanner.inference.SkipRules
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = KotlinLogging.logger {}

/**
 * Stage 1 of the scanner pipeline: enumerates every file beneath a library
 * root and emits one [FileEntry] per file the [SkipRules] don't filter out.
 *
 * Implementation notes:
 *  - Built on a recursive `SystemFileSystem.list` descent (kotlinx-io), so the
 *    same code compiles for the JVM server and the Kotlin/Native server. The
 *    descent prunes `@eaDir` directories and `.ignore`-flagged subtrees the
 *    moment [SkipRules] rejects them, never recursing beneath.
 *  - Children are visited in name-sorted order, so a scan is deterministic.
 *    Consumers are order-independent, but a stable order keeps logs and tests
 *    reproducible.
 *  - Symlinks are **not** followed. A symlink is emitted as a single leaf
 *    [FileEntry] (matching the old `Files.walkFileTree` no-follow behavior) but
 *    never descended into, so cycles can't crash the walk and files outside the
 *    library root aren't enumerated. Following symlinked libraries is a deferred
 *    feature.
 *  - `size`/`mtimeMs`/`inode` come from [statFile]; on filesystems without
 *    stable file keys (Windows default, FAT, some SMB mounts) `inode` is null
 *    and the Differ degrades to name-only cross-folder move detection.
 *  - [FileEntry.relPath] uses POSIX separators on every OS (kotlinx-io paths are
 *    already `/`-separated), so the wire format stays uniform.
 *  - The walk is collected into a list before emission. Library sizes are
 *    bounded (thousands of files, not a streamed firehose); the simplicity beats
 *    the backpressure precision of channelFlow here.
 */
internal class Walker(
    private val skipRules: (Path) -> Boolean = SkipRules::shouldSkip,
) {
    fun walk(root: Path): Flow<FileEntry> =
        flow {
            if (SystemFileSystem.metadataOrNull(root)?.isDirectory != true) return@flow
            val collected = mutableListOf<FileEntry>()
            descend(root, root, collected)
            collected.forEach { emit(it) }
        }.flowOn(fileIoDispatcher)

    private fun descend(
        root: Path,
        dir: Path,
        out: MutableList<FileEntry>,
    ) {
        val children =
            try {
                SystemFileSystem.list(dir).sortedBy { it.name }
            } catch (e: IOException) {
                // Never stranded: one unreadable directory must not fail the scan.
                logger.warn(e) { "walk: failed to list $dir — skipping subtree" }
                return
            }
        for (child in children) {
            if (skipRules(child)) continue
            // Symlinks are leaves: emit but never recurse (cycle-safe). The
            // isSymlink probe must precede the directory test because
            // metadataOrNull follows links, so a symlinked dir would otherwise
            // look recursable.
            if (isSymlink(child)) {
                emitFile(root, child, out)
                continue
            }
            if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) {
                descend(root, child, out)
            } else {
                emitFile(root, child, out)
            }
        }
    }

    private fun emitFile(
        root: Path,
        file: Path,
        out: MutableList<FileEntry>,
    ) {
        val attrs = statFile(file)
        if (attrs == null) {
            logger.warn { "walk: failed to stat $file — skipping" }
            return
        }
        val name = file.name
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        out +=
            FileEntry(
                relPath = file.relativeTo(root),
                name = name,
                ext = ext,
                size = attrs.size,
                mtimeMs = attrs.mtimeMs,
                inode = attrs.inode,
                fileType = FileTypeRules.classify(name),
            )
    }
}
