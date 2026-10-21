package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.AudioFile
import kotlin.math.roundToInt

/**
 * Audio-format summary derived from a book's files. Supplies the approximate-bitrate fallback for the
 * Book Detail "Bitrate" row when per-file bitrate is unavailable.
 *
 * @property codec upper-cased codec, e.g. "AAC".
 * @property approxBitrateKbps approximate average bitrate in kbps, or null when it can't be derived.
 */
data class AudioFormat(
    val codec: String,
    val approxBitrateKbps: Int?,
)

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
            .orEmpty()
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

/**
 * Pre-formatted audio-format strings for the Book Detail "Details" section, derived from a book's
 * audio files (the primary/first file carries the specs). Each field is null when its datum is
 * absent, so the UI can omit the corresponding row.
 */
data class AudioFormatDisplay(
    val format: String?,
    val bitrate: String?,
    val sampleRate: String?,
    val channels: String?,
)

/**
 * Builds the [AudioFormatDisplay] for [files]: format identity and sample-rate/channels come from
 * the primary (first) file; the bitrate prefers the primary file's exact value and falls back to
 * the size÷duration estimate ("~N kbps") across all files.
 */
fun audioFormatDisplay(files: List<AudioFile>): AudioFormatDisplay {
    val primary = files.firstOrNull()
    return AudioFormatDisplay(
        format = primary?.let { audioFormatIdentity(it.codec, it.codecProfile, it.spatial) },
        bitrate =
            bitrateLabel(primary?.bitrate)
                ?: audioFormatSummary(files)?.approxBitrateKbps?.takeIf { it > 0 }?.let { "~$it kbps" },
        sampleRate = sampleRateLabel(primary?.sampleRate),
        channels = channelsLabel(primary?.channels),
    )
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
    if (spatial?.run { trim().lowercase() } == "atmos") return "Dolby Atmos"
    val c = codec.trim().lowercase()
    if (c.isBlank()) return null
    return when (c) {
        "aac" -> aacProfileLabel(codecProfile)
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

/** AAC display name refined by its profile token (`xhe`/`hev2`/`he`); plain "AAC" otherwise. */
private fun aacProfileLabel(codecProfile: String?): String =
    when (codecProfile?.run { trim().lowercase() }) {
        "xhe" -> "xHE-AAC"
        "hev2" -> "HE-AAC v2"
        "he" -> "HE-AAC"
        else -> "AAC"
    }

/** Exact bitrate label — "320 kbps" from bits/sec; null when absent or non-positive. */
fun bitrateLabel(bitrate: Int?): String? {
    val kbps = bitrate?.let { (it / 1000.0).roundToInt() } ?: return null
    // A bitrate that rounds to nothing is unknown: "0 kbps" would be a confident wrong answer.
    return if (kbps > 0) "$kbps kbps" else null
}

/** Sample-rate label — "48 kHz" / "44.1 kHz" from hertz; null when absent or non-positive. */
fun sampleRateLabel(sampleRate: Int?): String? {
    if (sampleRate == null || sampleRate <= 0) return null
    val khz = sampleRate / 1000.0
    val text = if (khz == khz.toInt().toDouble()) khz.toInt().toString() else khz.toString()
    return "$text kHz"
}

private const val CHANNELS_5_1 = 6
private const val CHANNELS_7_1 = 8

/** Channel-count label — Mono/Stereo/5.1/7.1, else "N ch"; null when absent or non-positive. */
fun channelsLabel(channels: Int?): String? =
    when {
        channels == null || channels <= 0 -> null
        channels == 1 -> "Mono"
        channels == 2 -> "Stereo"
        channels == CHANNELS_5_1 -> "5.1"
        channels == CHANNELS_7_1 -> "7.1"
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

private const val BYTES_PER_KIB = 1024L
private const val BYTES_PER_MIB = BYTES_PER_KIB * 1024
private const val BYTES_PER_GIB = BYTES_PER_MIB * 1024
private const val TENTHS = 10L
private const val WHOLE_ABOVE_TENTHS = 1000L

/**
 * A size as every platform says it — "512 KB", "2.7 MB", "514 MB", "1.2 GB" — so a confirm, a Details row and the Files
 * & audio sheet agree on Android, iOS and web. Binary units; one decimal, dropped when it is .0 or the number passes
 * 100. Integer arithmetic, so a browser never prints "1.0000000000000002 GB".
 */
fun byteSizeLabel(bytes: Long): String {
    val size = bytes.coerceAtLeast(0L)
    return when {
        size < BYTES_PER_KIB -> "$size B"
        size < BYTES_PER_MIB -> "${size / BYTES_PER_KIB} KB"
        size < BYTES_PER_GIB -> tenthsLabel(size, BYTES_PER_MIB, "MB")
        else -> tenthsLabel(size, BYTES_PER_GIB, "GB")
    }
}

private fun tenthsLabel(
    bytes: Long,
    unit: Long,
    name: String,
): String {
    val tenths = (bytes * TENTHS + unit / 2) / unit
    return if (tenths >= WHOLE_ABOVE_TENTHS || tenths % TENTHS == 0L) {
        "${(tenths + TENTHS / 2) / TENTHS} $name"
    } else {
        "${tenths / TENTHS}.${tenths % TENTHS} $name"
    }
}
