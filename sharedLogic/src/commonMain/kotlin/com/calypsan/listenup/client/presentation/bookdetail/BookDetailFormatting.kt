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
    /**
     * "AAC · ~125 kbps" when both are known, the codec alone when only it is known, "~125 kbps" when
     * only the bitrate is known, and "" when neither is. Never an orphan bullet. The "~" marks the estimate.
     */
    fun displayLabel(): String =
        when {
            codec.isNotBlank() && approxBitrateKbps != null -> "$codec · ~$approxBitrateKbps kbps"
            codec.isNotBlank() -> codec
            approxBitrateKbps != null -> "~$approxBitrateKbps kbps"
            else -> ""
        }
}

/**
 * Derives a display [AudioFormat] from a book's audio files. The codec is the most common NON-BLANK
 * codec across the files (upper-cased), so a real codec wins even when some files carry a blank one;
 * it falls back to "" when every file is blank. The bitrate is an APPROXIMATE average — total bytes ×
 * 8 ÷ total seconds ÷ 1000 — so the UI labels it with "~", and is null when total duration or size is
 * non-positive. Returns null when [files] is empty or when there is nothing to show (blank codec AND
 * no bitrate).
 */
fun audioFormatSummary(files: List<AudioFile>): AudioFormat? {
    if (files.isEmpty()) return null
    val codec =
        files
            .map { it.codec.trim().uppercase() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: ""
    val totalBytes = files.sumOf { it.size }
    val totalMs = files.sumOf { it.duration }
    val bitrate =
        if (totalMs > 0 && totalBytes > 0) {
            (totalBytes * 8.0 / (totalMs / 1000.0) / 1000.0).roundToInt()
        } else {
            null
        }
    if (codec.isBlank() && bitrate == null) return null
    return AudioFormat(codec = codec, approxBitrateKbps = bitrate)
}

/** Maps an ISO 639-1 code (e.g. "en") to a display name, falling back to the upper-cased code. */
fun languageDisplayName(code: String): String {
    val key = code.trim().lowercase()
    return LANGUAGE_NAMES[key] ?: code.trim().uppercase()
}

/**
 * Human-readable audio-format identity for the Book Detail "Format" row. Spatial audio wins
 * (`spatial == "atmos"` → "Dolby Atmos"); otherwise the codec maps to a friendly name, with AAC
 * refined by its profile token. Unknown codecs are upper-cased; returns null when there's nothing
 * to show (blank codec and not spatial).
 */
fun audioFormatIdentity(
    codec: String,
    codecProfile: String?,
    spatial: String?,
): String? {
    if (spatial?.trim()?.lowercase() == "atmos") return "Dolby Atmos"
    val c = codec.trim().lowercase()
    if (c.isBlank()) return null
    return when (c) {
        "aac" ->
            when (codecProfile?.trim()?.lowercase()) {
                "xhe" -> "xHE-AAC"
                "hev2" -> "HE-AAC v2"
                "he" -> "HE-AAC"
                else -> "AAC"
            }
        "ac4" -> "AC-4"
        "eac3" -> "E-AC-3"
        "mp3" -> "MP3"
        "flac" -> "FLAC"
        "opus" -> "Opus"
        "alac" -> "ALAC"
        "vorbis" -> "Vorbis"
        else -> codec.trim().uppercase()
    }
}

/** Exact bitrate label — "320 kbps" from bits/sec; null when absent or non-positive. */
fun bitrateLabel(bitrate: Int?): String? {
    if (bitrate == null || bitrate <= 0) return null
    return "${(bitrate / 1000.0).roundToInt()} kbps"
}

/** Sample-rate label — "48 kHz" / "44.1 kHz" from hertz; null when absent or non-positive. */
fun sampleRateLabel(sampleRate: Int?): String? {
    if (sampleRate == null || sampleRate <= 0) return null
    val khz = sampleRate / 1000.0
    val text = if (khz == khz.toInt().toDouble()) khz.toInt().toString() else khz.toString()
    return "$text kHz"
}

/** Channel-count label — Mono/Stereo/5.1/7.1, else "N ch"; null when absent or non-positive. */
fun channelsLabel(channels: Int?): String? =
    when {
        channels == null || channels <= 0 -> null
        channels == 1 -> "Mono"
        channels == 2 -> "Stereo"
        channels == 6 -> "5.1"
        channels == 8 -> "7.1"
        else -> "$channels ch"
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
