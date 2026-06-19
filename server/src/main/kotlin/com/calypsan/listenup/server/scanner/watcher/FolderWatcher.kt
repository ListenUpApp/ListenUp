package com.calypsan.listenup.server.scanner.watcher

import com.calypsan.listenup.server.scanner.inference.MultiDiscPattern
import com.calypsan.listenup.server.scanner.inference.SkipRules
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Watches a library tree for filesystem changes and emits the
 * [bookRoot][Flow] of any directory affected. Consumers (the Scanner
 * orchestrator) then call `coordinator.reanalyze(bookRoot)`.
 *
 * Implementation notes:
 *
 *  - **Recursive watching.** The watcher engine tracks an explicit set of
 *    directories rather than recursing globally. We walk the library tree at
 *    [start] and register every non-skipped directory; directories created at
 *    runtime are picked up when their `Create` event arrives.
 *  - **Per-book coalescing.** Multiple events under the same book root
 *    within the [debouncer]'s settle window collapse to a single emission.
 *    Implementation: a map from book root → pending emission [Job]; a new
 *    event for an already-pending book root cancels the prior job and
 *    starts a fresh one. The "latest event wins" — its file is what we
 *    debounce on.
 *  - **Delete handling.** Delete events skip the debouncer (the file is
 *    gone, polling it would just time out) and emit immediately for the
 *    affected book root.
 *  - **Skip rules.** [SkipRules] applied to every event path. Dotfiles,
 *    `.ignore`-flagged subtrees, `@eaDir`, and temp extensions are
 *    invisible to the watcher just as they are to the Walker.
 *  - **Symlinks not followed** during initial registration (mirrors
 *    Walker behavior).
 *
 * The watcher does not start listening until [start] is called; cleanup
 * via [close] releases the inotify / FSEvents / RDC handles.
 */
internal class FolderWatcher(
    private val libraryRoot: Path,
    private val scope: CoroutineScope,
    private val debouncer: StableSizeDebouncer = StableSizeDebouncer(),
    private val skipRules: (Path) -> Boolean = SkipRules::shouldSkip,
    private val watcher: LowLevelDirectoryWatcher = RecursiveDirectoryWatcher(scope),
) {
    private val emissions = MutableSharedFlow<Path>(extraBufferCapacity = 64)
    val events: Flow<Path> = emissions.asSharedFlow()

    private val pendingByBookRoot = ConcurrentHashMap<Path, Job>()

    suspend fun start() {
        registerExistingTree()
        scope.launch {
            watcher.onEventFlow.collect { event ->
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.warn(e) { "watcher: failed to handle $event — continuing" }
                }
            }
        }
    }

    suspend fun close() {
        watcher.close()
    }

    private suspend fun registerExistingTree() {
        // One add() per directory rather than a single varargs spread call.
        // Avoids the array copy detekt's SpreadOperator rule warns about,
        // and keeps each watch independent — a single bad path doesn't fail
        // the whole registration batch.
        collectWatchableDirectories().forEach { watcher.add(it) }
    }

    private fun collectWatchableDirectories(): List<String> {
        val collected = mutableListOf<String>()
        Files.walkFileTree(
            libraryRoot,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != libraryRoot && skipRules(dir)) return FileVisitResult.SKIP_SUBTREE
                    collected += dir.toString()
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return collected
    }

    private suspend fun handle(event: DirectoryWatchEvent) {
        val fullPath = Path.of(event.targetDirectory).resolve(event.path)
        val isDelete = event.kind == DirectoryWatchEventKind.Delete
        // Skip rules must NOT filter delete events: the deleted path no longer exists
        // on disk, so filesystem probes inside skipRules (e.g. the `.ignore` sentinel
        // check) would evaluate a path that is gone. More importantly, a book directory
        // whose name happens to match a skip rule would silently drop the delete, so the
        // tombstone never lands. Skip-filter only non-delete events, exactly as the
        // Walker does when it descends a live tree.
        if (!isDelete && skipRules(fullPath)) return

        // A newly-created directory needs to be added to the watch set so events on
        // its children surface. The recursive watcher already registers new subtrees
        // itself, so this is a no-op guard kept for clarity of intent.
        if (event.kind == DirectoryWatchEventKind.Create && Files.isDirectory(fullPath)) {
            watcher.add(fullPath.toString())
        }

        val bookRoot = computeBookRoot(fullPath)
        scheduleEmission(bookRoot, fullPath, isDelete = isDelete)
    }

    private fun scheduleEmission(
        bookRoot: Path,
        triggerPath: Path,
        isDelete: Boolean,
    ) {
        pendingByBookRoot.compute(bookRoot) { _, existing ->
            existing?.cancel()
            scope.launch {
                if (!isDelete) {
                    // Wait for the file to settle. If it never does (deleted
                    // mid-wait), we still emit — the book root has changed.
                    debouncer.awaitStable(triggerPath)
                }
                emissions.tryEmit(bookRoot)
                pendingByBookRoot.remove(bookRoot)
            }
        }
    }

    /**
     * Computes the book root for a file or directory path. The book root is
     * the file's parent directory, or the parent's parent if the parent is
     * a multi-disc folder (`CD1`, `Disc 2`, …) — matching the Grouper.
     */
    private fun computeBookRoot(path: Path): Path {
        val parent = path.parent ?: return libraryRoot
        val parentName = parent.fileName?.toString() ?: return parent
        return if (MultiDiscPattern.matches(parentName)) {
            parent.parent ?: libraryRoot
        } else {
            parent
        }
    }
}
