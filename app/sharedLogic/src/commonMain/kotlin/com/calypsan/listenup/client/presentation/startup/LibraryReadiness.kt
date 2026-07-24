package com.calypsan.listenup.client.presentation.startup

import com.calypsan.listenup.client.domain.model.ScanProgressState

/**
 * The single authoritative answer to "what should the app show right now?" during the
 * authenticated-startup window — before the library shell is safe to mount.
 *
 * Replaces the five raw signals the navigation layer used to juggle (`isChecking`,
 * `needsLibrarySetup`, `setupCheckFailed`, `checkResolved`, `isServerScanning`) with one sealed
 * state the nav consumes via a single `when`. Produced by
 * [AppStartupViewModel.readiness] as a pure derivation of the setup-check state and the
 * sync layer's scan signal.
 *
 * The states are mutually exclusive and ordered by precedence: a server-setup question
 * ([Checking]/[CheckFailed]/[NeedsSetup]) always outranks population progress, which outranks
 * [Ready].
 */
sealed interface LibraryReadiness {
    /** The library-setup check is in flight (or hasn't resolved yet). Show the splash/loading gate. */
    data object Checking : LibraryReadiness

    /** An admin must create a library before anything else can happen. Drive the setup wizard. */
    data object NeedsSetup : LibraryReadiness

    /**
     * The initial library population is in progress and not yet visible in the client's Room — either
     * the server is still scanning or the client is still importing the scanned books (see
     * `applyScanEvent`). Show the dedicated populating screen and do NOT mount the shell. [progress]
     * is the live scan progress when available, null during the indeterminate "finishing up" import.
     *
     * [stalled] is the never-stranded escape hatch: it latches `true` once scan progress has gone
     * quiet for `AppStartupViewModel.POPULATING_STALL_TIMEOUT_MS` (a crashed/stuck server scan that
     * can never complete), so the screen can offer "Continue" into the partial library. A healthy
     * scan keeps advancing progress and never trips it.
     */
    data class Populating(
        val progress: ScanProgressState?,
        val stalled: Boolean = false,
    ) : LibraryReadiness

    /** A populated library is queryable in Room. Mount the shell. */
    data object Ready : LibraryReadiness

    /**
     * The setup check could not be completed (transient network/server error). Honest over silent:
     * surface a retryable error instead of dropping the admin into an empty shell.
     */
    data object CheckFailed : LibraryReadiness
}
