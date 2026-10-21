package com.calypsan.listenup.client.presentation.library

/** The library's length in its largest whole unit, for "248 books · 41 days of listening" (spec L4). */
sealed interface LibraryLength {
    /** Under a minute: no span is shown. */
    data object None : LibraryLength

    /** Whole days. */
    data class Days(
        val count: Long,
    ) : LibraryLength

    /** Whole hours, under a day. */
    data class Hours(
        val count: Long,
    ) : LibraryLength

    /** Whole minutes, under an hour. */
    data class Minutes(
        val count: Long,
    ) : LibraryLength
}

/** [totalMs] as a [LibraryLength]. */
fun libraryLengthOf(totalMs: Long): LibraryLength =
    when {
        totalMs >= MS_PER_DAY -> LibraryLength.Days(totalMs / MS_PER_DAY)
        totalMs >= MS_PER_HOUR -> LibraryLength.Hours(totalMs / MS_PER_HOUR)
        totalMs >= MS_PER_MINUTE -> LibraryLength.Minutes(totalMs / MS_PER_MINUTE)
        else -> LibraryLength.None
    }

private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_HOUR = 3_600_000L
private const val MS_PER_DAY = 86_400_000L
