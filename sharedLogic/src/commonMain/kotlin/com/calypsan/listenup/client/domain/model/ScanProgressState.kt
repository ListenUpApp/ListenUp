package com.calypsan.listenup.client.domain.model

/**
 * Current state of a library scan in progress. Null when no scan is running.
 */
data class ScanProgressState(
    val phase: String,
    val current: Int,
    val total: Int,
    val added: Int,
    val updated: Int,
    val removed: Int,
) {
    /** Human-readable phase name. */
    val phaseDisplayName: String
        get() =
            when (phase) {
                "walking" -> "Discovering files"
                "grouping" -> "Organizing"
                "analyzing" -> "Analyzing"
                "resolving" -> "Processing"
                "diffing" -> "Syncing"
                "applying" -> "Syncing"
                "complete" -> "Finishing up"
                else -> phase.replaceFirstChar { it.uppercase() }
            }

    /** Progress as a fraction (0.0 to 1.0), or null if total is 0. */
    val progressFraction: Float?
        get() = if (total > 0) current.toFloat() / total.toFloat() else null

    /** Summary of changes so far (e.g., "3 added, 1 updated"). */
    val changesSummary: String?
        get() {
            val parts = mutableListOf<String>()
            if (added > 0) parts.add("$added added")
            if (updated > 0) parts.add("$updated updated")
            if (removed > 0) parts.add("$removed removed")
            return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
}
