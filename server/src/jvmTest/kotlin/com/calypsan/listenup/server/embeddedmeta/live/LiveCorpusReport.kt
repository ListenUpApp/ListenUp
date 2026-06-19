package com.calypsan.listenup.server.embeddedmeta.live

import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import java.nio.file.Path

/**
 * Per-format roll-up populated by [LiveCorpusValidator]. Counts only files
 * the parser successfully turned into an [com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata];
 * failures land in [LiveCorpusReport.typedErrors] (and crashes in
 * [LiveCorpusReport.crashed]).
 */
internal data class FormatReport(
    val parsed: Int = 0,
    val withChapters: Int = 0,
    val withArtwork: Int = 0,
)

/**
 * Output of one live-corpus validation run.
 *
 * Distinguishes three failure modes operators care about:
 *
 * - [unsupportedByFormat] — file was a recognisable named format
 *   (FLAC/Ogg/Opus today) for which no parser is registered. Expected in
 *   the current registry; not a regression.
 * - [typedErrors] — the parser returned an [AudioMetadataError] subtype.
 *   Includes `UnsupportedFormat(format = null)` (magic bytes unrecognised
 *   entirely) since those represent files we genuinely can't classify.
 * - [crashed] — the parser threw an uncaught exception. **These are
 *   regressions.** The Definition of Done from spec §10.4 requires zero
 *   crashes against a real corpus.
 */
internal data class LiveCorpusReport(
    val totalFiles: Int,
    val byFormat: Map<AudioFormat, FormatReport>,
    val crashed: List<Pair<Path, Throwable>>,
    val typedErrors: List<Pair<Path, AudioMetadataError>>,
    val unsupportedByFormat: Map<AudioFormat, Int>,
) {
    fun formatLine(): String =
        buildString {
            appendLine("Live corpus validation report:")
            appendLine("  Total files: $totalFiles")
            byFormat.forEach { (format, report) ->
                appendLine(
                    "  ${format::class.simpleName}: ${report.parsed} parsed, " +
                        "${report.withChapters} with chapters, ${report.withArtwork} with artwork",
                )
            }
            if (unsupportedByFormat.isNotEmpty()) {
                appendLine("  Detected-but-deferred (parser not registered):")
                unsupportedByFormat.forEach { (f, n) -> appendLine("    ${f::class.simpleName}: $n files") }
            }
            if (crashed.isNotEmpty()) {
                appendLine("  CRASHES (${crashed.size}):")
                crashed.take(LOG_PREVIEW_LIMIT).forEach { (path, t) ->
                    appendLine("    $path: ${t::class.simpleName}: ${t.message}")
                }
            }
            if (typedErrors.isNotEmpty()) {
                appendLine("  TYPED ERRORS (${typedErrors.size}):")
                typedErrors.take(LOG_PREVIEW_LIMIT).forEach { (path, e) -> appendLine("    $path: ${e.code}") }
            }
        }

    companion object {
        private const val LOG_PREVIEW_LIMIT = 10
    }
}
