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
     * If [book] has a [CoverSource.Embedded] cover, writes its bytes to the spool and returns a
     * copy carrying a [CoverSource.Spooled] reference (and nulls `embedded.artwork`) — both copies
     * of the bytes leave the heap. Any other cover is returned unchanged. On write failure the book
     * is returned UNCHANGED so a disk hiccup never drops a cover (that one book stays in memory).
     */
    fun spoolCover(
        scanId: String,
        book: AnalyzedBook,
    ): AnalyzedBook {
        val cover = book.cover
        if (cover !is CoverSource.Embedded) return book
        return try {
            val dir = root.resolve(scanId)
            Files.createDirectories(dir)
            val file = dir.resolve(key(book.candidate.rootRelPath) + ".img")
            file.writeBytes(cover.artwork.bytes)
            book.copy(
                cover = CoverSource.Spooled(path = file.toString(), mime = cover.artwork.mime),
                embedded = book.embedded?.copy(artwork = null),
            )
        } catch (e: Exception) {
            logger.warn(e) { "cover spool write failed for ${book.candidate.rootRelPath}; keeping it in memory" }
            book
        }
    }

    /** Reads the spooled cover bytes back. */
    fun read(spooled: CoverSource.Spooled): ByteArray = Path.of(spooled.path).readBytes()

    /** Deletes the spool dir for [scanId]. Idempotent; never throws. */
    fun clearScan(scanId: String) {
        runCatching { root.resolve(scanId).toFile().deleteRecursively() }
            .onFailure { logger.warn(it) { "cover spool clear failed for scan $scanId" } }
    }

    /** Startup: delete every leftover scan dir from a crashed scan. Never throws. */
    fun sweepOrphans() {
        if (!root.exists()) return
        runCatching {
            Files.newDirectoryStream(root).use { stream -> stream.forEach { it.toFile().deleteRecursively() } }
        }.onFailure { logger.warn(it) { "cover spool orphan sweep failed" } }
    }

    private fun key(rootRelPath: String): String =
        MessageDigest.getInstance("SHA-256").digest(rootRelPath.toByteArray()).joinToString("") { "%02x".format(it) }
}
