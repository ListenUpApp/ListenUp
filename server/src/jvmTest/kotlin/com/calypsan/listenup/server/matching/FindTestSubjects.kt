package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.EditionFormat
import com.calypsan.listenup.api.dto.match.ExternalRef

internal const val MINUTE_MS = 60_000L

/** Your copy of Project Hail Mary, as Find's tests rank against it. */
internal fun yourCopyOfPhm(
    language: String? = "english",
    refs: List<ExternalRef> = emptyList(),
    format: EditionFormat? = EditionFormat.UNABRIDGED,
) = FindSubject(
    bookId = "b1",
    title = "Project Hail Mary",
    primaryAuthor = "Andy Weir",
    narrators = listOf("Ray Porter"),
    durationMs = 970 * MINUTE_MS,
    chapterCount = 36,
    year = 2021,
    format = format,
    asin = "B08G9PRS1K",
    isbn = "9780593135204",
    language = language,
    refs = refs,
)
