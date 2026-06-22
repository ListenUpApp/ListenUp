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
}
