package com.calypsan.listenup.server.embeddedmeta

import kotlin.math.log10

// Value parsers for pre-computed loudness/gain tags, shared by the MP3
// (TXXX:REPLAYGAIN_TRACK_GAIN) and MP4 (iTunNORM freeform) format readers.
// Both produce a dB gain clamped to a sane range, or null on any parse
// failure — a malformed gain tag never fails the file's metadata parse.

/** Gains outside ±24 dB are implausible for real material; clamp rather than trust them. */
private const val GAIN_CLAMP_DB = 24f

/** Sound Check words are volume adjustments per-mille of a 1000 base. */
private const val SOUND_CHECK_BASE = 1000.0

/** dB per decade of the Sound Check ratio: gain = -10 * log10(word / base). */
private const val DB_PER_DECADE = 10.0

/**
 * Parses a ReplayGain text value like `"-6.48 dB"` (the ` dB` suffix is optional and
 * case-insensitive) into a dB gain clamped to ±24 dB. Returns null when the value
 * is not a finite number.
 */
internal fun parseReplayGainDb(value: String): Float? {
    val trimmed = value.trim()
    val numeric = if (trimmed.endsWith("db", ignoreCase = true)) trimmed.dropLast(2).trim() else trimmed
    return numeric
        .toFloatOrNull()
        ?.takeIf { it.isFinite() }
        ?.coerceIn(-GAIN_CLAMP_DB, GAIN_CLAMP_DB)
}

/**
 * Parses an Apple Sound Check (`iTunNORM`) value — 8 or 10 space-separated 8-hex-digit
 * words — into a dB gain clamped to ±24 dB. The first two words are the L/R volume
 * adjustments relative to a 1000-per-mille base (`gain = -10 * log10(word / 1000)`);
 * the result is their average. Returns null on any parse failure (fewer than two
 * words, non-hex, or a word ≤ 0, whose log is undefined/infinite).
 */
internal fun parseITunNorm(value: String): Float? {
    val words = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size < 2) return null
    val left = soundCheckWordGainDb(words[0]) ?: return null
    val right = soundCheckWordGainDb(words[1]) ?: return null
    return ((left + right) / 2f)
        .takeIf { it.isFinite() }
        ?.coerceIn(-GAIN_CLAMP_DB, GAIN_CLAMP_DB)
}

private fun soundCheckWordGainDb(hex: String): Float? {
    val word = hex.toLongOrNull(radix = 16) ?: return null
    if (word <= 0) return null
    return (-DB_PER_DECADE * log10(word / SOUND_CHECK_BASE)).toFloat()
}
