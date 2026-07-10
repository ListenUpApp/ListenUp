package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.failure
import com.calypsan.listenup.server.io.hashBytesSha256
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
    private val suppressionTtlMs: Long = DEFAULT_SUPPRESSION_TTL_MS,
) {
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
    private suspend fun applyOp(op: WriteOp): AppResult<Unit> =
        try {
            when (op) {
                is WriteOp.EnsureDir -> {
                    createDirectoriesSuppressed(op.dir)
                    AppResult.Success(Unit)
                }

                is WriteOp.MoveFile -> {
                    applyMove(op)
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
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(LibraryWriteError.Unavailable(debugInfo = "${op::class.simpleName} failed: ${e.message}"))
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

    /** [WriteOp.MoveFile]'s idempotency rule — see its KDoc for the four-way case breakdown. */
    private fun applyMove(op: WriteOp.MoveFile): AppResult<Unit> {
        val fromExists = SystemFileSystem.exists(op.from)
        val toExists = SystemFileSystem.exists(op.to)
        return when {
            !fromExists && toExists -> {
                AppResult.Success(Unit) // already moved
            }

            fromExists && toExists -> {
                failure(LibraryWriteError.Unavailable(debugInfo = "ambiguous move: both ${op.from} and ${op.to} exist"))
            }

            !fromExists -> {
                failure(LibraryWriteError.Unavailable(debugInfo = "move source missing: ${op.from}"))
            }

            else -> {
                registry.register(op.from, suppressionTtlMs)
                registry.register(op.to, suppressionTtlMs)
                SystemFileSystem.atomicMove(op.from, op.to)
                AppResult.Success(Unit)
            }
        }
    }
}
