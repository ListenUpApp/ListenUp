package com.calypsan.listenup.client.core

/**
 * Makes [raw] safe to use as ONE path segment under a directory this app owns.
 *
 * Ids and file names reach the client from the server. A separator or a parent reference in one
 * would move the resulting path out of its directory, so separators and NUL become `_` and a
 * segment that is empty or a dot reference becomes `_`. Ordinary ids and file names pass through
 * unchanged, which keeps every path already on disk valid.
 *
 * Total — it never throws — because its callers return a path rather than an `AppResult`, and
 * because the same input must always map to the same path so a later read finds what an earlier
 * write stored.
 */
internal fun safePathSegment(raw: String): String {
    val cleaned = raw.map { if (it == '/' || it == '\\' || it == '\u0000') '_' else it }.joinToString("")
    return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "_" else cleaned
}
