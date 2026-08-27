package com.calypsan.listenup.server.organize

/** Fallback name used when a segment sanitizes down to nothing at all. */
private const val UNTITLED = "Untitled"

/** The longest a single sanitized path segment may be. */
private const val MAX_SEGMENT_LENGTH = 120

/** Characters illegal (or reserved) in a path segment on at least one of Windows/macOS/Linux. */
private val ILLEGAL_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

/**
 * The organizer's fixed cross-platform path-segment sanitization policy (spec: not a knob —
 * every preset×knob combination round-trips through this unconditionally). Strips
 * filesystem-illegal characters and control characters, collapses whitespace runs to a single
 * space, trims leading/trailing dots and spaces (illegal as a trailing character on Windows),
 * caps segment length, and falls back to [UNTITLED] rather than ever producing an empty segment.
 *
 * [sanitize] is idempotent — `sanitize(sanitize(x)) == sanitize(x)` — so re-planning an
 * already-organized path is always a no-op.
 */
object PathSanitizer {
    /** Sanitizes [input] into a single safe path segment per the fixed policy documented on this object. */
    fun sanitize(input: String): String {
        // Normalize every whitespace variant (tab, newline, CR, ...) to a plain space FIRST —
        // otherwise tabs/newlines (code points < 0x20) would be stripped as control characters
        // in the next step instead of collapsing into a separator space.
        val whitespaceNormalized = input.map { if (it.isWhitespace()) ' ' else it }.joinToString("")

        val stripped =
            whitespaceNormalized
                .filterNot { it in ILLEGAL_CHARS || it.code < 0x20 }
                .replace(Regex(" +"), " ")
                .trim(' ', '.')

        val capped = stripped.take(MAX_SEGMENT_LENGTH).trim(' ', '.')

        return capped.ifEmpty { UNTITLED }
    }
}
