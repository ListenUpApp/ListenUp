package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.isSymlink
import com.calypsan.listenup.server.io.listDirectory
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

/** The recovery folder ext-family filesystems create at their root; usually unreadable to the server. */
private const val LOST_AND_FOUND = "lost+found"

/**
 * Stage 1 of the scanner pipeline: enumerates every file beneath a library
 * root and emits one [FileEntry] per file the [SkipRules] don't filter out.
 *
 * Implementation notes:
 *  - Built on a recursive [listDirectory] descent (kotlinx-io paths), so the
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
    /**
     * Walks [root]. Every directory that cannot be listed and every file that cannot be stat'ed is
     * skipped and handed to [onUnreadable]: the walk must not fail on one of them, but a caller that
     * treats "not walked" as "gone" has to know the difference between the two.
     */
    fun walk(
        root: Path,
        containmentRoots: List<Path> = listOf(root),
        onUnreadable: (Path) -> Unit = {},
    ): Flow<FileEntry> =
        flow {
            if (SystemFileSystem.metadataOrNull(root)?.isDirectory != true) return@flow
            val collected = mutableListOf<FileEntry>()
            descend(
                root = root,
                dir = root,
                containmentRoots = containmentRoots,
                out = collected,
                onUnreadable = onUnreadable,
            )
            collected.forEach { emit(it) }
        }.flowOn(fileIoDispatcher)

    private fun descend(
        root: Path,
        dir: Path,
        containmentRoots: List<Path>,
        out: MutableList<FileEntry>,
        onUnreadable: (Path) -> Unit,
    ) {
        // `.ignore` sentinel: one probe as we enter the directory prunes the whole subtree, instead
        // of an exists() syscall for every walked entry (100k+ redundant probes on a large NAS).
        if (hasIgnoreSentinel(dir)) return
        val children =
            try {
                listDirectory(dir).sortedBy { it.name }
            } catch (e: IOException) {
                // Never stranded: one unreadable directory must not fail the scan.
                logger.warn(e) { "walk: failed to list $dir — skipping subtree" }
                onUnreadable(dir)
                return
            }
        for (child in children) {
            if (skipByName(child)) continue
            // Root level only: that is where a filesystem creates its recovery folder, and it is never a book.
            if (child.name == LOST_AND_FOUND && containmentRoots.any { it.toString() == dir.toString() }) continue
            // The isSymlink probe precedes every other test: metadataOrNull follows links, so a
            // linked directory would otherwise look recursable and a linked file would look plain.
            if (isSymlink(child)) {
                if (isRegularFileInsideAnyRoot(child, containmentRoots)) {
                    emitFile(root = root, file = child, out = out, onUnreadable = onUnreadable)
                } else {
                    logger.warn { "walk: $child is a link that does not lead to a file inside the library — skipping" }
                }
                continue
            }
            val meta = SystemFileSystem.metadataOrNull(child)
            when {
                meta?.isDirectory == true -> {
                    descend(
                        root = root,
                        dir = child,
                        containmentRoots = containmentRoots,
                        out = out,
                        onUnreadable = onUnreadable,
                    )
                }

                meta?.isRegularFile == true -> {
                    emitFile(root = root, file = child, out = out, onUnreadable = onUnreadable)
                }

                else -> {
                    logger.warn { "walk: $child is not a regular file — skipping" }
                }
            }
        }
    }

    private fun emitFile(
        root: Path,
        file: Path,
        out: MutableList<FileEntry>,
        onUnreadable: (Path) -> Unit,
    ) {
        val attrs = statFile(file)
        if (attrs == null) {
            logger.warn { "walk: failed to stat $file — skipping" }
            onUnreadable(file)
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
