package com.calypsan.listenup.api.dto.scanner

import kotlinx.serialization.Serializable

/** Where the scanner is in its pipeline. Reported in progress events. */
@Serializable
enum class ScanPhase {
    WALKING,
    GROUPING,
    ANALYZING,
    DIFFING,
    PERSISTING,
    COMPLETED,

    /**
     * What an older app reads a phase a newer server added as, so the progress stream stays alive
     * and the UI shows generic "working". The scanner never reports it.
     */
    UNKNOWN,
}
