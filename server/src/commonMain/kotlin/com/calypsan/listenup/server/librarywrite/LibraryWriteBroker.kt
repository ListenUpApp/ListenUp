package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.failure
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.io.isSymlink
import com.calypsan.listenup.server.io.isUnder
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.uuid.Uuid

private val logger = loggerFor<LibraryWriteBroker>()

/** Default self-write suppression window — long enough for the watcher's debounce settle window to elapse. */
private const val DEFAULT_SUPPRESSION_TTL_MS = 30_000L

/**
 * The only component permitted to write inside library folders (Konsist-pinned — see
 * `LibraryWritesGoThroughBrokerRule`). Guarantees: watcher self-write suppression via
 * [SelfWriteRegistry], atomic visibility (temp file + rename), journaled crash-resumable
 * multi-op manifests via [WriteJournal], and typed degradation on unwritable roots.
 *
 * The broker has no feature knowledge — it moves bytes. Sidecar/organize/upload semantics live
 * in their own domains and call through this seam.
 */
class LibraryWriteBroker(
    private val registry: SelfWriteRegistry,
    private val journal: WriteJournal,
    private val libraryRoots: LibraryRootProvider,
    private val suppressionTtlMs: Long = DEFAULT_SUPPRESSION_TTL_MS,
) {
    /**
     * The first of [paths] that does not resolve inside a live library folder, or `null` when all
     * of them do. Consulted before any byte moves, on every path an operation touches — a move
     * has two, and only checking the destination would let a caller move a file *out* of the
     * library just as easily as into it.
     */
    private suspend fun firstOutsideLibrary(vararg paths: Path): Path? {
        val live = libraryRoots.roots()
        return paths.firstOrNull { !isInsideAnyRoot(it, live) }
    }

    /**
     * Writes [bytes] to [target] atomically: staged to a sibling temp file, then renamed into
     * place, so a concurrent reader (or the watcher) never observes a partial file. Both the temp
     * and target paths are registered with [registry] *before* either is touched on disk, so
     * every filesystem event the write produces is swallowed as a self-write. On any I/O failure
     * the target's claim is released (no write landed, so no matching filesystem event will ever
     * arrive) and the caller gets a typed [LibraryWriteError.Unavailable] — never a thrown
     * exception.
     */
    suspend fun writeFile(
        target: Path,
        bytes: ByteArray,
    ): AppResult<WrittenFile> {
        firstOutsideLibrary(target)?.let { escaping ->
            logger.warn { "refused a write that resolves outside every library folder: $escaping" }
            return failure(
                LibraryWriteError.OutsideLibrary(debugInfo = "$escaping does not resolve inside any library folder"),
            )
        }
        val parent =
            target.parent
                ?: return failure(LibraryWriteError.Unavailable(debugInfo = "no parent directory: $target"))
        val tmp = Path(parent, ".listenup-tmp-${Uuid.random()}")
        return try {
            createDirectoriesSuppressed(parent)
            registry.register(target, suppressionTtlMs)
            registry.register(tmp, suppressionTtlMs)
            SystemFileSystem.sink(tmp).buffered().use { it.write(bytes) }
            SystemFileSystem.atomicMove(tmp, target)
            AppResult.Success(WrittenFile(target, hashBytesSha256(bytes)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            registry.release(target)
            registry.release(tmp)
            logger.warn(e) { "writeFile failed for $target" }
            failure(LibraryWriteError.Unavailable(debugInfo = "$target: ${e.message}"))
        }
    }

    /**
     * Probes whether [root] is currently writable: creates a marker file and immediately deletes
     * it, reporting [LibraryWriteStatus.Available] on success. The marker is registered with
     * [registry] before it's created, so the create+delete pair is swallowed as a self-write.
     * The probe observes and never mutates the root itself — a missing root (disconnected mount)
     * reports [LibraryWriteStatus.Unavailable] rather than being silently created. Never throws —
     * an I/O failure at any step also reports [LibraryWriteStatus.Unavailable].
     */
    suspend fun probe(root: Path): LibraryWriteStatus {
        if (!SystemFileSystem.exists(root)) {
            return LibraryWriteStatus.Unavailable(reason = "$root: does not exist")
        }
        val marker = Path(root, ".listenup-probe-${Uuid.random()}")
        return try {
            registry.register(marker, suppressionTtlMs)
            SystemFileSystem.sink(marker).buffered().use { it.write(ByteArray(0)) }
            SystemFileSystem.delete(marker, mustExist = false)
            LibraryWriteStatus.Available
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            registry.release(marker)
            LibraryWriteStatus.Unavailable(reason = "$root: ${e.message}")
        }
    }

    /**
     * Executes [manifest]'s ops in order. The manifest is journaled *before* its first op runs,
     * so a crash mid-manifest leaves a resumable trail for [recoverJournal] to pick up at the
     * next boot. Stops at the first op that fails, leaving the journal in place for a retry —
     * ops already marked done in the journal are never re-applied. On full success the journal
     * entry is deleted.
     */
    suspend fun executeManifest(manifest: WriteManifest): AppResult<Unit> =
        try {
            journal.persist(manifest)
            applyFrom(manifest, doneFlags = List(manifest.ops.size) { false })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "failed to journal manifest ${manifest.opId}" }
            failure(
                LibraryWriteError.Unavailable(debugInfo = "journal persist failed for ${manifest.opId}: ${e.message}"),
            )
        }

    /**
     * Re-applies every un-done op of every manifest still in the journal — the crash-resume path,
     * called once at boot (before the watcher starts) so an interrupted [executeManifest] finishes
     * instead of leaving the library folder half-changed forever. Idempotent: a manifest whose ops
     * are already all done (or whose journal entry is simply absent) is a no-op. Never throws —
     * a manifest that still can't complete (e.g. its root is still unwritable) stays in the
     * journal and is retried on the next boot; other pending manifests still get their turn.
     */
    suspend fun recoverJournal() {
        for (pending in journal.listPending()) {
            applyFrom(pending.manifest, pending.doneFlags)
        }
    }

    /** Applies [manifest]'s ops from the first un-done index onward, journaling progress as it goes. */
    private suspend fun applyFrom(
        manifest: WriteManifest,
        doneFlags: List<Boolean>,
    ): AppResult<Unit> {
        for ((index, op) in manifest.ops.withIndex()) {
            if (doneFlags[index]) continue
            val result = applyOp(op)
            if (result is AppResult.Failure) return result
            journal.markOpDone(manifest.opId, index)
        }
        journal.delete(manifest.opId)
        return AppResult.Success(Unit)
    }

    /**
     * Applies a single [WriteOp], per the idempotency rule documented on its type (see
     * [WriteOp]'s KDoc). Never throws — any I/O failure becomes a typed
     * [LibraryWriteError.Unavailable].
     */
    private suspend fun applyOp(op: WriteOp): AppResult<Unit> {
        val touched =
            when (op) {
                is WriteOp.EnsureDir -> arrayOf(op.dir)

                is WriteOp.MoveFile -> arrayOf(op.from, op.to)

                // Only the destination is a library path; the source is staging, and its own
                // containment is checked by [refuseUnlessImportable] below.
                is WriteOp.ImportFile -> arrayOf(op.to)

                is WriteOp.WriteFile -> arrayOf(op.target)

                is WriteOp.DeleteFile -> arrayOf(op.target)

                is WriteOp.DeleteDirIfEmpty -> arrayOf(op.dir)

                // Containment is necessary but nowhere near sufficient here — see
                // [refuseUnlessRecursivelyDeletable], which runs below.
                is WriteOp.DeleteDir -> arrayOf(op.dir)
            }
        firstOutsideLibrary(*touched)?.let { escaping ->
            logger.warn { "refused ${op::class.simpleName} that resolves outside every library folder: $escaping" }
            return failure(
                LibraryWriteError.OutsideLibrary(debugInfo = "$escaping does not resolve inside any library folder"),
            )
        }
        refusalFor(op)?.let { return it }
        return try {
            when (op) {
                is WriteOp.EnsureDir -> {
                    createDirectoriesSuppressed(op.dir)
                    AppResult.Success(Unit)
                }

                is WriteOp.MoveFile -> {
                    applyMove(op.from, op.to)
                }

                is WriteOp.ImportFile -> {
                    applyImport(op)
                }

                is WriteOp.WriteFile -> {
                    writeFile(op.target, op.bytes).let { result ->
                        if (result is AppResult.Failure) result else AppResult.Success(Unit)
                    }
                }

                is WriteOp.DeleteFile -> {
                    registry.register(op.target, suppressionTtlMs)
                    SystemFileSystem.delete(op.target, mustExist = false)
                    AppResult.Success(Unit)
                }

                is WriteOp.DeleteDirIfEmpty -> {
                    deleteDirIfEmpty(op.dir)
                    AppResult.Success(Unit)
                }

                is WriteOp.DeleteDir -> {
                    deleteDirRecursively(op.dir)
                    AppResult.Success(Unit)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(LibraryWriteError.Unavailable(debugInfo = "${op::class.simpleName} failed: ${e.message}"))
        }
    }

    /**
     * Creates [dir] (and any missing ancestors), registering every directory that does not yet
     * exist with [registry] *before* it's created. A directory-create fires its own kernel event
     * at the watcher — proven by the WatcherSuppression integration test — so the directories the
     * broker brings into existence need claims exactly like the files it writes.
     */
    private fun createDirectoriesSuppressed(dir: Path) {
        var missing: Path? = dir
        while (missing != null && !SystemFileSystem.exists(missing)) {
            registry.register(missing, suppressionTtlMs)
            missing = missing.parent
        }
        SystemFileSystem.createDirectories(dir)
    }

    /**
     * [WriteOp.DeleteDirIfEmpty]'s idempotency rule — see its KDoc. A missing directory is a
     * silent no-op; a directory with contents is left alone (best-effort cleanup only).
     */
    private fun deleteDirIfEmpty(dir: Path) {
        if (!SystemFileSystem.exists(dir)) return
        if (SystemFileSystem.list(dir).isNotEmpty()) return
        registry.register(dir, suppressionTtlMs)
        SystemFileSystem.delete(dir, mustExist = false)
    }

    /**
     * [WriteOp.DeleteDir]'s body — see its KDoc for the idempotency rule and the refusals that
     * gate it. Post-order (children before parents), and every path is claimed with [registry]
     * before it is unlinked so the watcher swallows the whole burst as self-writes.
     *
     * Deliberately NOT `com.calypsan.listenup.server.io.deleteRecursively`: that one asks
     * `metadataOrNull`, which follows symbolic links, so a link to a directory reads as a directory
     * and the walk descends through it — deleting somebody else's files and reporting success. Here
     * a link is a leaf, always, whatever it points at.
     */
    private fun deleteDirRecursively(dir: Path) {
        if (!SystemFileSystem.exists(dir) && !isSymlink(dir)) return
        if (!isSymlink(dir) && SystemFileSystem.metadataOrNull(dir)?.isDirectory == true) {
            for (child in SystemFileSystem.list(dir)) deleteDirRecursively(child)
        }
        registry.register(dir, suppressionTtlMs)
        SystemFileSystem.delete(dir, mustExist = false)
    }

    /**
     * The extra refusals a given op kind carries beyond the containment check every op gets.
     *
     * Gathered into one dispatch rather than a run of `if (op is ...)` lines inside `applyOp`: the
     * guards are the load-bearing part of this class and read better named together, and `applyOp`
     * stays a description of what each op *does*.
     */
    private suspend fun refusalFor(op: WriteOp): AppResult<Unit>? =
        when (op) {
            is WriteOp.ImportFile -> refuseUnlessImportable(op)
            is WriteOp.DeleteDirIfEmpty -> refuseIfLibraryRoot(op.dir, "DeleteDirIfEmpty")
            is WriteOp.DeleteDir -> refuseUnlessRecursivelyDeletable(op)
            else -> null
        }

    /**
     * Refuses [dir] when it IS a live library folder root. Shared by both directory-removing ops,
     * because containment cannot answer this one: a root resolves inside itself, so
     * `firstOutsideLibrary` waves it through, and removing one would leave every book row in that
     * folder pointing at nothing.
     *
     * [WriteOp.DeleteDir] has always needed it. [WriteOp.DeleteDirIfEmpty] needs it as of Delete
     * Book's ancestor walk: "delete it only if empty" sounds self-limiting, but an empty library
     * folder is exactly the state a freshly-emptied library is in, and that is the moment the walk
     * is closest to the root. The guard is what makes a caller that gets the arithmetic wrong
     * harmless rather than catastrophic.
     */
    private suspend fun refuseIfLibraryRoot(
        dir: Path,
        opName: String,
    ): AppResult<Unit>? {
        val resolved = resolvedForContainment(dir)
        if (libraryRoots.roots().any { resolvedForContainment(it) == resolved }) {
            logger.warn { "refused $opName of a library folder root: $dir" }
            return failure(
                LibraryWriteError.ProtectedPath(debugInfo = "$dir is a library folder root"),
            )
        }
        return null
    }

    /**
     * The two refusals [WriteOp.DeleteDir] carries beyond the containment check every op gets —
     * see its KDoc. Returns the typed refusal, or null when the recursive delete may proceed.
     *
     * Both questions are the ones containment cannot answer. The library-root half is shared with
     * [WriteOp.DeleteDirIfEmpty] via [refuseIfLibraryRoot]. The symlink half is this op's alone: a
     * symbolic link named as a book directory resolves inside the library whenever its target does
     * — but "unlink this" and "walk this and unlink everything under it" are different operations,
     * and only the first is ever what a caller naming a link meant.
     */
    private suspend fun refuseUnlessRecursivelyDeletable(op: WriteOp.DeleteDir): AppResult<Unit>? {
        refuseIfLibraryRoot(op.dir, "DeleteDir")?.let { return it }
        if (isSymlink(op.dir)) {
            logger.warn { "refused DeleteDir of a symbolic link: ${op.dir}" }
            return failure(
                LibraryWriteError.ProtectedPath(debugInfo = "${op.dir} is a symbolic link, not a directory"),
            )
        }
        return null
    }

    /**
     * The extra containment [WriteOp.ImportFile] carries beyond the destination check every op
     * gets — see its KDoc. Returns the typed refusal, or null when the op may proceed.
     *
     * Two questions, both asked on *resolved* paths so `..` and symlinks cannot dodge them:
     * does the source really live under the declared staging root, and is that staging root
     * really outside the library? Together they pin ImportFile to bringing content in, and stop
     * it standing in for a [WriteOp.MoveFile] whose two-sided check the caller would rather skip.
     */
    private suspend fun refuseUnlessImportable(op: WriteOp.ImportFile): AppResult<Unit>? {
        val resolvedRoot = resolvedForContainment(op.fromRoot)
        if (!resolvedForContainment(op.from).isUnder(resolvedRoot)) {
            logger.warn { "refused ImportFile whose source escapes its staging root: ${op.from} !under ${op.fromRoot}" }
            return failure(
                LibraryWriteError.OutsideLibrary(debugInfo = "${op.from} does not resolve inside ${op.fromRoot}"),
            )
        }
        if (isInsideAnyRoot(op.fromRoot, libraryRoots.roots())) {
            logger.warn { "refused ImportFile whose staging root is inside a library folder: ${op.fromRoot}" }
            return failure(
                LibraryWriteError.OutsideLibrary(
                    debugInfo = "${op.fromRoot} resolves inside a library folder — use MoveFile",
                ),
            )
        }
        return null
    }

    /**
     * [WriteOp.ImportFile]: same idempotency rule as [WriteOp.MoveFile], but the move itself has
     * to survive the source and destination living on **different filesystems** — staging sits
     * under `$LISTENUP_HOME` and a library folder is very often a separate mount, where
     * `rename(2)` fails with `EXDEV` rather than copying.
     *
     * So: try the atomic rename first (free when they share a device, the common single-disk
     * case), and fall back to copy-into-a-sibling-temp + rename + delete-source. The fallback
     * keeps the destination's atomic visibility — a reader or the watcher never sees a partial
     * file at [WriteOp.ImportFile.to], only the finished one appearing in a single rename. Both
     * the temp and the final path are claimed with [registry] before either exists, exactly as
     * [writeFile] does.
     */
    private fun applyImport(op: WriteOp.ImportFile): AppResult<Unit> {
        val fromExists = SystemFileSystem.exists(op.from)
        val toExists = SystemFileSystem.exists(op.to)
        return when {
            !fromExists && toExists -> {
                AppResult.Success(Unit)
            }

            // already imported
            fromExists && toExists -> {
                failure(
                    LibraryWriteError.Unavailable(debugInfo = "ambiguous import: both ${op.from} and ${op.to} exist"),
                )
            }

            !fromExists -> {
                failure(LibraryWriteError.Unavailable(debugInfo = "import source missing: ${op.from}"))
            }

            else -> {
                importBytes(op)
            }
        }
    }

    /** The two-strategy body of [applyImport] — atomic rename when possible, copy + rename when not. */
    private fun importBytes(op: WriteOp.ImportFile): AppResult<Unit> {
        val parent =
            op.to.parent
                ?: return failure(LibraryWriteError.Unavailable(debugInfo = "no parent directory: ${op.to}"))
        val tmp = Path(parent, ".listenup-tmp-${Uuid.random()}")
        registry.register(op.to, suppressionTtlMs)
        registry.register(tmp, suppressionTtlMs)
        return try {
            SystemFileSystem.atomicMove(op.from, op.to)
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (crossDevice: Exception) {
            logger.debug(crossDevice) { "atomic import rename unavailable for ${op.from} — copying instead" }
            try {
                SystemFileSystem.source(op.from).use { input ->
                    SystemFileSystem.sink(tmp).buffered().use { it.transferFrom(input) }
                }
                SystemFileSystem.atomicMove(tmp, op.to)
                SystemFileSystem.delete(op.from, mustExist = false)
                AppResult.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SystemFileSystem.delete(tmp, mustExist = false)
                registry.release(op.to)
                registry.release(tmp)
                logger.warn(e) { "import failed for ${op.from} -> ${op.to}" }
                failure(LibraryWriteError.Unavailable(debugInfo = "${op.to}: ${e.message}"))
            }
        }
    }

    /** [WriteOp.MoveFile]'s idempotency rule — see its KDoc for the four-way case breakdown. */
    private fun applyMove(
        from: Path,
        to: Path,
    ): AppResult<Unit> {
        val fromExists = SystemFileSystem.exists(from)
        val toExists = SystemFileSystem.exists(to)
        return when {
            !fromExists && toExists -> {
                AppResult.Success(Unit) // already moved
            }

            fromExists && toExists -> {
                failure(LibraryWriteError.Unavailable(debugInfo = "ambiguous move: both $from and $to exist"))
            }

            !fromExists -> {
                failure(LibraryWriteError.Unavailable(debugInfo = "move source missing: $from"))
            }

            else -> {
                registry.register(from, suppressionTtlMs)
                registry.register(to, suppressionTtlMs)
                SystemFileSystem.atomicMove(from, to)
                AppResult.Success(Unit)
            }
        }
    }
}
