package com.calypsan.listenup.server.embeddedmeta

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata

/**
 * Per-format parser contract — the extension point for the embeddedmeta
 * package. One implementation per supported audio format. Implementations
 * live under `:server.embeddedmeta.format.<name>` (e.g. `format.mp3.Mp3Parser`).
 *
 * New parsers register via the `embeddedmetaModule` Koin module (Task 52a) —
 * adding a format does not require editing the dispatcher
 * ([EmbeddedMetadataParser]) or any central routing code.
 *
 * On failure, return `AppResult.Failure` carrying a typed
 * [com.calypsan.listenup.api.error.AudioMetadataError] subtype:
 * - [com.calypsan.listenup.api.error.AudioMetadataError.CorruptHeader] when
 *   format-specific structure is malformed
 * - [com.calypsan.listenup.api.error.AudioMetadataError.TruncatedStream] when
 *   the file ended before declared structure was complete
 * - [com.calypsan.listenup.api.error.AudioMetadataError.IoError] when the
 *   underlying [SeekableAudioSource] fails
 *
 * **Adding a new audio format:**
 *
 * 1. Add the format's magic-byte signature to [AudioFormatDetector] if not
 *    already recognised. Add a variant to [AudioFormat] (commonMain) only if
 *    the format is genuinely new — most won't be.
 * 2. Create `<Format>Parser` under `:server.embeddedmeta.format.<name>`
 *    implementing this interface. Reference `Mp3Parser` or `Mp4Parser` for the
 *    binary-decoding shape, error mapping, and chapter-merging conventions.
 * 3. Register the parser in `embeddedmetaModule`. Add a synthetic fixture
 *    builder + Kotest property tests. The Konsist rule
 *    `embeddedMetaTypesInCommonMain` pins the package layout. The entry point
 *    and dispatcher do not change.
 */
internal interface AudioFormatParser {
    /** Formats this parser handles. Must be non-empty. */
    val supports: Set<AudioFormat>

    /**
     * Parse the embedded metadata for the audio stream referenced by [source].
     * Caller is responsible for opening and closing [source].
     */
    suspend fun parse(source: SeekableAudioSource): AppResult<EmbeddedAudioMetadata>
}
