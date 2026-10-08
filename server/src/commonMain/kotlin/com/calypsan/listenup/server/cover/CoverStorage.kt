package com.calypsan.listenup.server.cover

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.librarywrite.LibraryWriteBroker
import com.calypsan.listenup.server.librarywrite.WriteManifest
import com.calypsan.listenup.server.librarywrite.WriteOp
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

private val logger = loggerFor<CoverStorage>()

/**
 * Filesystem-side counterpart to the cover-state column on `books`: the only
 * place that touches an on-disk cover image during a delete.
 *
 * ListenUp does **not** maintain a managed cover directory. Filesystem-source
 * covers live inside the book's library folder (`<libraryRoot>/<rootRelPath>/cover.*`
 * or the first sibling image) — exactly where the scanner found them.
 * [BookRepository.coverInfo][com.calypsan.listenup.server.services.BookRepository.coverInfo]
 * resolves that path on demand; this class accepts an already-resolved [Path]
 * and best-effort-removes it.
 *
 * The split with [BookServiceImpl][com.calypsan.listenup.server.api.BookServiceImpl]
 * is deliberate. The DB nullification happens inside a transaction so the
 * revision-bump and the change-bus fire atomically with the row update.
 * The file delete runs **after** the transaction commits — if the row update
 * fails, the file is untouched. If the file delete fails (file already gone,
 * permission issue, scanner-deleted-it-already), the row update has already
 * taken effect; the file becomes orphaned and gets swept by the orphan-image
 * cleanup pass later. Never inverted, never inlined.
 *
 * **Embedded covers have no file of their own** — the artwork lives inside the
 * primary audio file, which we must never delete. Callers detect the embedded
 * source from the [CoverPayload.source][com.calypsan.listenup.api.sync.CoverPayload.source]
 * field on the book payload and skip calling this class entirely.
 *
 * The file sits inside a library folder, so the delete goes through [broker] like every other
 * library write: it is refused if the path resolves outside every library folder (a book
 * directory swapped for a link to another disk, say), and the watcher swallows it as a self-write.
 *
 * Stateless. Constructed once at startup; safe for concurrent use.
 */
class CoverStorage(
    private val broker: LibraryWriteBroker,
) {
    /**
     * Best-effort delete of the file at [path]. Idempotent: a non-existent
     * file is not an error. Any failure — including a refusal from [broker] — is
     * logged at WARN and swallowed, so a flaky filesystem can't break the RPC contract.
     */
    suspend fun delete(path: Path) {
        val result =
            broker.executeManifest(
                WriteManifest(
                    opId = "delete-cover-${Uuid.random()}",
                    ops = listOf(WriteOp.DeleteFile(path)),
                    // The caller has already moved on; a refused cover delete must not run itself
                    // at the next boot. The orphan sweep is the fallback, as it always was.
                    resumeAfterReportedFailure = false,
                ),
            )
        if (result is AppResult.Failure) {
            logger.warn { "CoverStorage.delete failed for path=$path: ${result.error.debugInfo ?: result.error.code}" }
        }
    }
}
