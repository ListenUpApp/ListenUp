package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.isSymlink
import com.calypsan.listenup.server.io.relativeTo
import com.calypsan.listenup.server.io.statFile
import com.calypsan.listenup.server.librarywrite.isRegularFileInsideAnyRoot
import com.calypsan.listenup.server.scanner.inference.FileTypeRules
import com.calypsan.listenup.server.scanner.inference.SkipRules
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = loggerFor<Walker>()

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
 *  - Links are never descended, so cycles can't crash the walk. A link to a file is emitted
 *    only when it resolves to a regular file inside one of the `containmentRoots` (the
 *    library's folder roots); a link that leaves them, dangles, loops or points at a directory
 *    is skipped with a warning. Devices, FIFOs and sockets are never emitted — opening a FIFO
 *    blocks forever and would hold the library's scan lock.
 *  - `size`/`mtimeMs` come from [statFile]. `inode` is the file's inode on the native server;
 *    on the JVM it is null on filesystems without stable file keys (Windows default, FAT, some
 *    SMB mounts) and the Differ degrades to name-only cross-folder move detection.
 *  - [FileEntry.relPath] uses POSIX separators on every OS (kotlinx-io paths are
 *    already `/`-separated), so the wire format stays uniform.
 *  - The walk is collected into a list before emission. Library sizes are
 *    bounded (thousands of files, not a streamed firehose); the simplicity beats
 *    the backpressure precision of channelFlow here.
 */
internal class Walker(
    private val skipByName: (Path) -> Boolean = SkipRules::shouldSkipByName,
    private val hasIgnoreSentinel: (Path) -> Boolean = SkipRules::hasIgnoreSentinel,
) {
    fun walk(
        root: Path,
        containmentRoots: List<Path> = listOf(root),
    ): Flow<FileEntry> =
        flow {
            if (SystemFileSystem.metadataOrNull(root)?.isDirectory != true) return@flow
            val collected = mutableListOf<FileEntry>()
            descend(root, root, containmentRoots, collected)
            collected.forEach { emit(it) }
        }.flowOn(fileIoDispatcher)

    private fun descend(
        root: Path,
        dir: Path,
        containmentRoots: List<Path>,
        out: MutableList<FileEntry>,
    ) {
        // `.ignore` sentinel: one probe as we enter the directory prunes the whole subtree, instead
        // of an exists() syscall for every walked entry (100k+ redundant probes on a large NAS).
        if (hasIgnoreSentinel(dir)) return
        val children =
            try {
                SystemFileSystem.list(dir).sortedBy { it.name }
            } catch (e: IOException) {
                // Never stranded: one unreadable directory must not fail the scan.
                logger.warn(e) { "walk: failed to list $dir — skipping subtree" }
                return
            }
        for (child in children) {
            if (skipByName(child)) continue
            // The isSymlink probe precedes every other test: metadataOrNull follows links, so a
            // linked directory would otherwise look recursable and a linked file would look plain.
            if (isSymlink(child)) {
                if (isRegularFileInsideAnyRoot(child, containmentRoots)) {
                    emitFile(root, child, out)
                } else {
                    logger.warn { "walk: $child is a link that does not lead to a file inside the library — skipping" }
                }
                continue
            }
            val meta = SystemFileSystem.metadataOrNull(child)
            when {
                meta?.isDirectory == true -> descend(root, child, containmentRoots, out)
                meta?.isRegularFile == true -> emitFile(root, child, out)
                else -> logger.warn { "walk: $child is not a regular file — skipping" }
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
                // The walker only ever emits files discovered beneath root, so relativeTo is
                // non-null here; the filename fallback covers the unreachable non-descendant case.
                relPath = file.relativeTo(root) ?: file.name,
                name = name,
                ext = ext,
                size = attrs.size,
                mtimeMs = attrs.mtimeMs,
                inode = attrs.inode,
                fileType = FileTypeRules.classify(name),
            )
    }
}
