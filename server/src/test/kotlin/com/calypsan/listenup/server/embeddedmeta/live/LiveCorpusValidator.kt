package com.calypsan.listenup.server.embeddedmeta.live

import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.streams.asSequence
import java.nio.file.Path as JPath

/**
 * Walks a directory tree and runs [EmbeddedMetadataParser] against every
 * file with an audio extension. Used by the env-gated
 * [LiveCorpusValidationTest] to validate the parser registry against a
 * real corpus — the per-parser Definition of Done from spec §10.4.
 *
 * **Scope (refinement spec §2.1):** registered parsers cover MP3 + MP4
 * only. The walker visits FLAC/Ogg/Opus files too so the report names
 * them in the detected-but-deferred bucket; the parser surfaces them as
 * [AudioMetadataError.UnsupportedFormat] rather than crashes.
 */
internal class LiveCorpusValidator(
    private val parser: EmbeddedMetadataParser,
) {
    fun validate(rootDir: JPath): LiveCorpusReport {
        val byFormat = mutableMapOf<AudioFormat, FormatReport>()
        val crashed = mutableListOf<Pair<JPath, Throwable>>()
        val typedErrors = mutableListOf<Pair<JPath, AudioMetadataError>>()
        val unsupportedByFormat = mutableMapOf<AudioFormat, Int>()
        var total = 0

        Files.walk(rootDir).use { stream ->
            stream
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { it.extension.lowercase() in AUDIO_EXTENSIONS }
                .forEach { file ->
                    total++
                    classify(file, byFormat, unsupportedByFormat, typedErrors, crashed)
                }
        }
        return LiveCorpusReport(
            totalFiles = total,
            byFormat = byFormat.toMap(),
            crashed = crashed.toList(),
            typedErrors = typedErrors.toList(),
            unsupportedByFormat = unsupportedByFormat.toMap(),
        )
    }

    @Suppress("TooGenericExceptionCaught") // Crash bucket is the whole point.
    private fun classify(
        file: JPath,
        byFormat: MutableMap<AudioFormat, FormatReport>,
        unsupportedByFormat: MutableMap<AudioFormat, Int>,
        typedErrors: MutableList<Pair<JPath, AudioMetadataError>>,
        crashed: MutableList<Pair<JPath, Throwable>>,
    ) {
        try {
            when (val result = runBlocking { parser.parse(Path(file.toAbsolutePath().toString())) }) {
                is AppResult.Success -> {
                    val parsed = result.data
                    byFormat.merge(
                        parsed.format,
                        FormatReport(
                            parsed = 1,
                            withChapters = if (parsed.chapters.isNotEmpty()) 1 else 0,
                            withArtwork = if (parsed.artwork != null) 1 else 0,
                        ),
                    ) { old, new ->
                        old.copy(
                            parsed = old.parsed + new.parsed,
                            withChapters = old.withChapters + new.withChapters,
                            withArtwork = old.withArtwork + new.withArtwork,
                        )
                    }
                }
                is AppResult.Failure -> {
                    when (val err = result.error) {
                        is AudioMetadataError.UnsupportedFormat -> {
                            val format = err.format
                            if (format != null) {
                                unsupportedByFormat.merge(format, 1, Int::plus)
                            } else {
                                // Magic unrecognised — surface for inspection rather than silently
                                // dropping; an operator may want to add a new format to the detector.
                                typedErrors += file to err
                            }
                        }
                        is AudioMetadataError -> typedErrors += file to err
                        else -> Unit // Parser only returns AudioMetadataError; other AppError shouldn't reach here.
                    }
                }
            }
        } catch (e: Throwable) {
            crashed += file to e
        }
    }

    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b")
    }
}
