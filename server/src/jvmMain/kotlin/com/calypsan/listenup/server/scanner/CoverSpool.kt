package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CoverSource
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

private val logger = KotlinLogging.logger {}

/**
 * Scan-scoped on-disk staging for embedded cover bytes, so the scanner never holds all of a
 * library's covers in heap at once. During the analyze drain each embedded cover is written to
 * `<root>/<scanId>/<key>.img` and the in-memory [AnalyzedBook] keeps only a [CoverSource.Spooled]
 * reference; [com.calypsan.listenup.server.services.BookPersister] reads each back one at a time
 * at persist; the scan's dir is cleared when persist finishes; a dir left by a crashed scan is
 * swept at startup. [root] is `$LISTENUP_HOME/scan-spool`.
 */
class CoverSpool(
    private val root: Path,
) {
    /**
     * Frees embedded artwork bytes from every book (regardless of cover source), then spools the
     * cover bytes to disk when the cover is [CoverSource.Embedded].
     *
     * **Why bytes-only, not null:** [embedded.artwork] is the scan-quality "w/artwork" marker read
     * by [com.calypsan.listenup.server.scanner.Scanner.toEmbeddedScanCounters] — nulling it would
     * corrupt the diagnostic counter. Replacing `.bytes` with an empty array keeps the marker intact
     * while releasing the memory. The artwork bytes in `cover` (a distinct reference) are unaffected
     * and are written to disk as normal.
     *
     * Books with a filesystem cover ([CoverSource.Filesystem]) or no cover at all previously
     * leaked ~250 MB of embedded artwork bytes when a `cover.jpg` took precedence — this path
     * now frees those bytes too.
     *
     * On write failure the cover is kept in memory (unchanged); only the redundant
     * `embedded.artwork` bytes are still emptied so the peak-heap savings are never forfeited.
     */
    fun spoolCover(
        scanId: String,
        book: AnalyzedBook,
    ): AnalyzedBook {
        // Free embedded artwork BYTES from every book — redundant once the cover is resolved (the
        // persister reads `cover`, never `embedded.artwork`). Keep the EmbeddedArtwork marker with
        // empty bytes so the scan-quality "w/artwork" diagnostic still counts the book.
        val em = book.embedded
        val artwork = em?.artwork
        val lightenedEmbedded =
            if (artwork != null && artwork.bytes.isNotEmpty()) {
                em.copy(artwork = artwork.copy(bytes = ByteArray(0)))
            } else {
                em
            }
        val cover = book.cover
        if (cover !is CoverSource.Embedded) {
            return book.copy(embedded = lightenedEmbedded)
        }
        return try {
            val dir = root.resolve(scanId)
            Files.createDirectories(dir)
            val file = dir.resolve(key(book.candidate.rootRelPath) + ".img")
            file.writeBytes(cover.artwork.bytes)
            book.copy(
                cover = CoverSource.Spooled(path = file.toString(), mime = cover.artwork.mime),
                embedded = lightenedEmbedded,
            )
        } catch (e: Exception) {
            logger.warn(e) { "cover spool write failed for ${book.candidate.rootRelPath}; keeping it in memory" }
            book.copy(embedded = lightenedEmbedded)
        }
    }

    /** Reads the spooled cover bytes back. */
    fun read(spooled: CoverSource.Spooled): ByteArray = Path.of(spooled.path).readBytes()

    /** Deletes the spool dir for [scanId]. Idempotent; never throws. */
    fun clearScan(scanId: String) {
        runCatching { root.resolve(scanId).toFile().deleteRecursively() }
            .onFailure { logger.warn(it) { "cover spool clear failed for scan $scanId" } }
    }

    /**
     * Startup: delete leftover scan dirs from a crashed scan — but only those untouched for at
     * least [ORPHAN_GRACE_MS]. A live scan's dir is modified on every cover write, so it stays far
     * under this window; recently-modified dirs are therefore left alone. This stops a fresh boot's
     * sweep from deleting a **concurrent** instance's in-flight scan dir out from under its persist
     * (the cause of the spooled-cover 404s in #703 when a stale JVM still shares this data dir).
     * Never throws.
     */
    fun sweepOrphans() {
        if (!root.exists()) return
        val cutoff = System.currentTimeMillis() - ORPHAN_GRACE_MS
        runCatching {
            Files.newDirectoryStream(root).use { stream ->
                stream.forEach { dir ->
                    val lastModified = runCatching { Files.getLastModifiedTime(dir).toMillis() }.getOrDefault(0L)
                    if (lastModified <= cutoff) {
                        dir.toFile().deleteRecursively()
                    } else {
                        logger.info {
                            "cover spool sweep: keeping recent dir ${dir.fileName} " +
                                "(modified ${System.currentTimeMillis() - lastModified}ms ago, under grace) — " +
                                "another scan may be live"
                        }
                    }
                }
            }
        }.onFailure { logger.warn(it) { "cover spool orphan sweep failed" } }
    }

    private fun key(rootRelPath: String): String =
        MessageDigest.getInstance("SHA-256").digest(rootRelPath.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * A spool dir untouched for at least this long is treated as a crashed-scan orphan and
         * swept at startup. A live scan rewrites its dir on every cover spool, so it never ages
         * past this window — the grace is what keeps the startup sweep from clobbering a live
         * (possibly other-process) scan.
         */
        const val ORPHAN_GRACE_MS: Long = 30 * 60 * 1000L // 30 minutes
    }
}
