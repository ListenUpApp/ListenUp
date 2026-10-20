package com.calypsan.listenup.client.presentation.library

import com.calypsan.listenup.client.domain.model.ScanProgressState

/**
 * What the Library's one status-strip slot says above the grid (spec §3.1.1 L27/L28). Every platform draws this;
 * none decides it, so a scan and a first sync read the same on Android, iOS and web.
 */
sealed interface LibraryStatusStrip {
    /** Nothing to say: the slot is empty. */
    data object Hidden : LibraryStatusStrip

    /** The server is scanning. Outranks [Arriving] because it can say how far along it is. */
    data class Scanning(
        val progress: ScanProgressState,
    ) : LibraryStatusStrip

    /**
     * The library's first population, or this device's first catch-up, is still arriving. [booksSoFar] is what Room
     * holds now; the client never knows the server's total during a sync pull, so there is no "of N".
     */
    data class Arriving(
        val booksSoFar: Int,
    ) : LibraryStatusStrip
}

/**
 * The strip for these inputs. A scan needs both its flag and its progress: `scanProgress` alone can outlive the scan
 * it describes (the gate Android's banner and web's strip already used).
 */
internal fun libraryStatusStrip(
    isServerScanning: Boolean,
    scanProgress: ScanProgressState?,
    isLibraryArriving: Boolean,
    booksSoFar: Int,
): LibraryStatusStrip =
    when {
        isServerScanning && scanProgress != null -> LibraryStatusStrip.Scanning(scanProgress)
        isLibraryArriving -> LibraryStatusStrip.Arriving(booksSoFar)
        else -> LibraryStatusStrip.Hidden
    }
