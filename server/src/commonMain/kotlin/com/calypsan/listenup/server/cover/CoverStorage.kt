package com.calypsan.listenup.server.cover

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = KotlinLogging.logger {}

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
 * Stateless. Constructed once at startup; safe for concurrent use.
 */
class CoverStorage {
    /**
     * Best-effort delete of the file at [path]. Idempotent: a non-existent
     * file is not an error. Any failure is logged at WARN and swallowed —
     * never thrown — so a flaky filesystem can't break the RPC contract.
     */
    fun delete(path: Path) {
        try {
            SystemFileSystem.delete(path, mustExist = false)
        } catch (e: Exception) {
            logger.warn(e) { "CoverStorage.delete failed for path=$path" }
        }
    }
}
