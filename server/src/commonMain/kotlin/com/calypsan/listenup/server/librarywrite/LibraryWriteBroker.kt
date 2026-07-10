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
 * [SelfWriteRegistry], atomic visibility (temp file + rename), and typed degradation on
 * unwritable roots. Journaled multi-op manifests ([executeManifest]) land in a later phase of
 * this component.
 *
 * The broker has no feature knowledge — it moves bytes. Sidecar/organize/upload semantics live
 * in their own domains and call through this seam.
 */
class LibraryWriteBroker(
    private val registry: SelfWriteRegistry,
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
            SystemFileSystem.createDirectories(parent)
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
     * Never throws — an I/O failure at any step reports [LibraryWriteStatus.Unavailable].
     */
    suspend fun probe(root: Path): LibraryWriteStatus {
        val marker = Path(root, ".listenup-probe-${Uuid.random()}")
        return try {
            SystemFileSystem.createDirectories(root)
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
}
