package com.calypsan.listenup.client.voice

/**
 * Represents the resolved intent from a voice command.
 * Platform adapters convert this to native playback actions.
 */
sealed class PlaybackIntent {
    /** Play a specific book by ID */
    data class PlayBook(
        val bookId: String,
    ) : PlaybackIntent()

    /** Resume the most recent in-progress book */
    data object Resume : PlaybackIntent()

    /** Play from a specific point in a series */
    data class PlaySeriesFrom(
        val seriesId: String,
        val startBookId: String,
    ) : PlaybackIntent()

    /** Multiple viable matches - platform decides how to handle */
    data class Ambiguous(
        val candidates: List<ResolvedMatch>,
        val bestGuess: ResolvedMatch?,
    ) : PlaybackIntent()

    /** No matches found for the query */
    data class NotFound(
        val originalQuery: String,
    ) : PlaybackIntent()
}

/**
 * A matched book with confidence score.
 */
data class ResolvedMatch(
    val bookId: String,
    val title: String,
    val authorNames: String?,
    val confidence: Float,
)
