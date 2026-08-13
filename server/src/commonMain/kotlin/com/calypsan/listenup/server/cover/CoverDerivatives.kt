package com.calypsan.listenup.server.cover

import com.calypsan.listenup.server.imaging.decodeImage
import com.calypsan.listenup.server.imaging.encodeJpeg
import com.calypsan.listenup.server.imaging.resizedTo
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.readBytes
import com.calypsan.listenup.server.io.writeBytesAtomically
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.util.KeyedMutex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val log = loggerFor<CoverDerivatives>()

/**
 * Smaller renderings of a book cover, cached on disk and generated on demand.
 *
 * **Generation on demand is the authoritative path, not an optimisation.** A cover URL is
 * content-addressed by the artwork's hash and served `immutable` for a year, so a width that
 * silently fell back to full-size bytes would be cached that way forever — a derivative appearing
 * later could never be seen, because the URL that would show it never changes. Every sized request
 * therefore either produces the derivative or declines outright; a warm-up that populates the cache
 * ahead of time is free to fail, because nothing depends on it having run.
 *
 * **Keyed by the cover hash, not the book id.** New artwork is a new hash is a new file, so a
 * re-covered book needs no invalidation anywhere — which matters because cover bytes are written
 * from five call sites across scan, enrich, lookup-apply and upload, and an invalidation hook would
 * have to be correct in every one of them. The cost is that a deleted book leaves its derivatives behind;
 * they are a cache, and a sweep is the right way to reclaim them.
 *
 * **Declining is normal.** A cover the decoder cannot reach — an undecodable stream, a WebP, or a
 * source too small for the reduction the width needs — yields `null`, and the caller serves the
 * original bytes. Nothing here fails a request. A decline is deliberately **not** cached: what the
 * decoder cannot reach today it may reach after a codec change, and a stored "no" keyed by nothing
 * but the artwork would outlive the reason for it.
 *
 * ⛔ **[baseDir] must not live under `$LISTENUP_HOME/covers`.** `BackupArchive` walks that directory
 * *recursively* into every archive and folds it into a rolling checksum, so derivatives stored
 * inside it would bloat every backup with bytes that regenerate for free — and change the covers
 * checksum for archives that hold no new artwork at all.
 *
 * @param baseDir the directory derivatives are cached in (`$LISTENUP_HOME/cache/covers`).
 */
class CoverDerivatives(
    private val baseDir: Path,
) {
    private val inFlight = KeyedMutex()

    /**
     * The ladder rung to serve for a display width of [requestedWidth] physical pixels: the
     * smallest rung that still covers it, or `null` when the request is past the top of the ladder
     * and the original is the right answer.
     *
     * Rounding **up** is the whole point — sharpness is never traded for bytes, so a tile that
     * needs 301px gets 600 rather than a soft 300. The ladder is also what bounds this cache: only
     * a rung reaches [derivative], so an arbitrary width in a URL cannot mint a new cache entry.
     */
    fun rungFor(requestedWidth: Int): Int? = RUNGS.firstOrNull { it >= requestedWidth }

    /**
     * The cover with hash [coverHash] rendered at [width] pixels wide, from cache when it is there
     * and generated from [original] when it is not — or `null` when it cannot be produced.
     *
     * [original] is called at most once per key even under a burst of concurrent callers, and only
     * on a cache miss, so it can be an expensive read of the full-size bytes.
     */
    suspend fun derivative(
        coverHash: String,
        width: Int,
        original: suspend () -> ByteArray?,
    ): ByteArray? {
        require(width > 0) { "width must be positive, got $width" }
        val file = resolve(coverHash, width) ?: return null
        cached(file)?.let { return it }

        return inFlight.withLock(file.name) {
            // Re-check under the key's lock — a racing caller may have generated it already.
            cached(file) ?: generate(file, width, original)
        }
    }

    private suspend fun generate(
        file: Path,
        width: Int,
        original: suspend () -> ByteArray?,
    ): ByteArray? {
        val source = original() ?: return null
        val rendered = render(source, width) ?: return null
        persist(file, rendered)
        return rendered
    }

    /**
     * Decodes [source] at the cheapest scale that still covers [width], resizes precisely, and
     * re-encodes. Runs on [Dispatchers.Default]: this is the only CPU-bound work the request path
     * does, and it must not sit on a thread that is meant to be serving other requests.
     */
    private suspend fun render(
        source: ByteArray,
        width: Int,
    ): ByteArray? =
        withContext(Dispatchers.Default) {
            try {
                decodeImage(source, width)?.resizedTo(width)?.let { encodeJpeg(it, QUALITY) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Cover derivative render failed at ${width}px — serving the original" }
                null
            }
        }

    private suspend fun cached(file: Path): ByteArray? =
        withContext(fileIoDispatcher) {
            if (SystemFileSystem.metadataOrNull(file)?.isRegularFile == true) file.readBytes() else null
        }

    /** Best-effort: a derivative we generated but could not store is still the right answer to return. */
    private suspend fun persist(
        file: Path,
        bytes: ByteArray,
    ) {
        withContext(fileIoDispatcher) {
            try {
                SystemFileSystem.createDirectories(baseDir)
                file.writeBytesAtomically(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Cover derivative cache write failed for ${file.name}" }
            }
        }
    }

    /**
     * `<baseDir>/<hash>@<width>.jpg`, or `null` when [coverHash] carries path-traversal characters.
     * The hash reaches here from the database rather than from a URL, so this is a guard against a
     * future caller passing something else, not against today's one.
     */
    private fun resolve(
        coverHash: String,
        width: Int,
    ): Path? {
        if (coverHash.isEmpty() || ".." in coverHash || "/" in coverHash || "\\" in coverHash) return null
        return Path(baseDir, "$coverHash@$width.jpg")
    }

    private companion object {
        /**
         * The ladder, ascending. Two rungs, because the decoder reconstructs only 1/8 and 1/4
         * scales: 600 is the most a 2400px cover can reach, and 300 covers a library grid tile at
         * 1x. A 1200px rung would need a 1/2 reduction — new transform code, not a new constant.
         */
        val RUNGS = listOf(300, 600)

        /** Measured at 213KB for a 1200px cover; visually indistinguishable from the source at tile size. */
        const val QUALITY = 85
    }
}
