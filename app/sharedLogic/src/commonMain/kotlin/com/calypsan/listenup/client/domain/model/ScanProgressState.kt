package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.event.ScanBookRef
import kotlin.math.roundToInt

/**
 * Current state of a library scan in progress. Null when no scan is running. Carries both the
 * granular sync counters (existing) and the rich stats driving the "Building your library" screen.
 */
data class ScanProgressState(
    val phase: String,
    val current: Int,
    val total: Int,
    val added: Int,
    val updated: Int,
    val removed: Int,
    val filesTotal: Int = 0,
    val books: Int = 0,
    val booksTotal: Int = 0,
    val authors: Int = 0,
    val durationMs: Long = 0,
    val currentFile: String? = null,
    val recentBooks: List<ScanBookRef> = emptyList(),
    val startedAtMs: Long = 0,
) {
    val phaseDisplayName: String
        get() =
            when (phase) {
                "walking" -> "Discovering files"
                "grouping" -> "Organizing"
                "analyzing" -> "Analyzing"
                "resolving" -> "Processing"
                "diffing" -> "Syncing"
                "applying" -> "Syncing"
                "persisting" -> "Saving library"
                "complete" -> "Finishing up"
                else -> phase.replaceFirstChar { it.uppercase() }
            }

    /**
     * Progress fraction (0..1) over analyzed books, or null while the book total is unknown
     * (WALKING/GROUPING → indeterminate bar). Driven by booksAnalyzed/booksTotal so it advances
     * through the long ANALYZING phase instead of pinning at 100% the instant file-walking ends.
     */
    val progressFraction: Float?
        get() = if (booksTotal > 0) (books.toFloat() / booksTotal).coerceIn(0f, 1f) else null

    /** Total matched audio rounded to whole hours, for the "Hours" stat. */
    val hours: Int
        get() = (durationMs / 3_600_000.0).roundToInt()

    /**
     * The label shown under the bar during the PERSISTING phase — "Saving N of M" over the books
     * being written. Distinct from the ANALYZING label (a percentage + ETA) because the persist
     * phase has no meaningful per-scan ETA: the bar restarts at 0 after analysis already elapsed.
     */
    val savingLabel: String
        get() = "Saving $books of $booksTotal"

    val changesSummary: String?
        get() {
            val parts = mutableListOf<String>()
            if (added > 0) parts.add("$added added")
            if (updated > 0) parts.add("$updated updated")
            if (removed > 0) parts.add("$removed removed")
            return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
}
