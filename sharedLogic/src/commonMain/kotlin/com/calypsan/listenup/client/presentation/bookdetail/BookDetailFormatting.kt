package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.AudioFile
import kotlin.math.roundToInt

/**
 * Audio-format summary shown in the Book Detail "Details" section.
 *
 * @property codec upper-cased codec, e.g. "AAC".
 * @property approxBitrateKbps approximate average bitrate in kbps, or null when it can't be derived.
 */
data class AudioFormat(
    val codec: String,
    val approxBitrateKbps: Int?,
) {
    /** "AAC · ~125 kbps" when a bitrate is known, otherwise just the codec. The "~" marks the estimate. */
    fun displayLabel(): String = if (approxBitrateKbps != null) "$codec · ~$approxBitrateKbps kbps" else codec
}

/**
 * Derives a display [AudioFormat] from a book's audio files. The codec is the most common codec
 * across the files (upper-cased); the bitrate is an APPROXIMATE average — total bytes × 8 ÷ total
 * seconds ÷ 1000 — so the UI labels it with "~". Returns null when [files] is empty; the bitrate is
 * null (codec still shown) when total duration or size is non-positive.
 */
fun audioFormatSummary(files: List<AudioFile>): AudioFormat? {
    if (files.isEmpty()) return null
    val codec =
        files
            .groupingBy { it.codec.uppercase() }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: return null
    val totalBytes = files.sumOf { it.size }
    val totalMs = files.sumOf { it.duration }
    val bitrate =
        if (totalMs > 0 && totalBytes > 0) {
            (totalBytes * 8.0 / (totalMs / 1000.0) / 1000.0).roundToInt()
        } else {
            null
        }
    return AudioFormat(codec = codec, approxBitrateKbps = bitrate)
}

/** Maps an ISO 639-1 code (e.g. "en") to a display name, falling back to the upper-cased code. */
fun languageDisplayName(code: String): String {
    val key = code.trim().lowercase()
    return LANGUAGE_NAMES[key] ?: code.trim().uppercase()
}

private val LANGUAGE_NAMES: Map<String, String> =
    mapOf(
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "nl" to "Dutch",
        "ru" to "Russian",
        "ja" to "Japanese",
        "zh" to "Chinese",
        "ko" to "Korean",
        "pl" to "Polish",
        "sv" to "Swedish",
        "da" to "Danish",
        "no" to "Norwegian",
        "fi" to "Finnish",
        "cs" to "Czech",
        "tr" to "Turkish",
        "ar" to "Arabic",
        "hi" to "Hindi",
    )
