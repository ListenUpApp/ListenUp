package com.calypsan.listenup.server.embeddedmeta

import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.io.fileIoDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.text.HexFormat

/**
 * Public entry point for the embedded-metadata parser.
 *
 * Dispatches detected formats to the first matching [AudioFormatParser] in
 * [parsers]. The `embeddedmetaModule` Koin module wires today's registry —
 * `Mp3Parser`, `Mp4Parser` — and is the only place adding a new parser
 * touches dispatch logic. [sourceFactory] is injectable for tests.
 *
 * Cancellation: polls [currentCoroutineContext]'s [ensureActive] between
 * detection and dispatch. Long-running per-format parses respect coroutine
 * cancellation through the same pattern in their own bodies.
 *
 * Metrics (e.g. Micrometer counters/timers) are deliberately not wired here
 * yet — the project doesn't have a server-wide observability strategy in
 * place. Add them via a dedicated metrics-recording follow-up; the entry
 * point's narrow surface and the typed [AudioMetadataError] subtypes give a
 * clean instrumentation point when that lands.
 */
internal open class EmbeddedMetadataParser(
    private val detector: AudioFormatDetector,
    private val parsers: List<AudioFormatParser>,
    private val sourceFactory: (Path) -> SeekableAudioSource = ::defaultSeekableSource,
) {
    open suspend fun parse(path: Path): AppResult<EmbeddedAudioMetadata> =
        withContext(fileIoDispatcher) {
            val source =
                try {
                    sourceFactory(path)
                } catch (e: IOException) {
                    return@withContext AppResult.Failure(
                        AudioMetadataError.IoError(
                            pathString = path.toString(),
                            ioMessage = e.message ?: "unknown IO error",
                            debugInfo = e.message,
                        ),
                    )
                }

            source.use { src ->
                src.seek(0)
                val head = src.readFully(AudioFormatDetector.MIN_HEADER_BYTES)
                src.seek(0)
                val format = detector.detect(head)
                if (format == null) {
                    return@use AppResult.Failure(
                        AudioMetadataError.UnsupportedFormat(
                            pathString = path.toString(),
                            detectedMagic = head.copyOf(MAGIC_HEX_BYTES).toHexString(HexFormat.UpperCase),
                            format = null,
                        ),
                    )
                }
                currentCoroutineContext().ensureActive()
                val parser = parsers.firstOrNull { format in it.supports }
                if (parser == null) {
                    return@use AppResult.Failure(
                        AudioMetadataError.UnsupportedFormat(
                            pathString = path.toString(),
                            detectedMagic = head.copyOf(MAGIC_HEX_BYTES).toHexString(HexFormat.UpperCase),
                            format = format,
                        ),
                    )
                }
                parser.parse(src)
            }
        }

    companion object {
        private const val MAGIC_HEX_BYTES: Int = 4
    }
}
