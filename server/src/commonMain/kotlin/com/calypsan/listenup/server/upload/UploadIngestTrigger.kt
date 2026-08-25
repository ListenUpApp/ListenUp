package com.calypsan.listenup.server.upload

import kotlinx.io.files.Path

/**
 * Asks the scanner to re-analyse one book directory that has just appeared in the library.
 *
 * A seam rather than a direct call into the scan orchestrator for one reason: uploads write
 * through the library-write broker, which **suppresses the file watcher** so the server never
 * reacts to its own writes. That suppression is exactly right for an organizer move — the book
 * already exists in the database and its row is updated in the same step — but an uploaded book
 * has no row yet, so something has to say "this one is new, go and look". This interface is that
 * sentence, and keeping it an interface lets the finalize pipeline be tested without booting a
 * scanner.
 *
 * Fire-and-forget by design: the scan coordinator serialises and de-duplicates the work, and the
 * upload response is about where the files landed, not about when the row appears.
 */
internal fun interface UploadIngestTrigger {
    /** Queues an incremental re-analysis of [bookRoot], an absolute path inside a library folder. */
    fun reanalyze(bookRoot: Path)
}
