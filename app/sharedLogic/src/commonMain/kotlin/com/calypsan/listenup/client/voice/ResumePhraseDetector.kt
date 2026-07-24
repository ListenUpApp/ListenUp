package com.calypsan.listenup.client.voice

/**
 * Detects whether a voice query is requesting to resume playback
 * rather than searching for a specific title.
 */
object ResumePhraseDetector {
    private val RESUME_PHRASES =
        setOf(
            "resume",
            "continue",
            "continue listening",
            "continue reading",
            "my audiobook",
            "my book",
            "where i left off",
            "pick up where i left off",
            "keep playing",
            "keep listening",
        )

    /**
     * Returns true if the query indicates a resume intent.
     * Matches exact phrases or phrases contained within larger queries.
     */
    fun isResumeIntent(query: String): Boolean {
        val normalized = query.lowercase().trim()
        if (normalized.isEmpty()) return false

        return RESUME_PHRASES.any { phrase ->
            normalized == phrase || normalized.contains(phrase)
        }
    }
}
