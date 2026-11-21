package com.calypsan.listenup.client.domain.model

/**
 * Why a download row is [DownloadStatus.PAUSED].
 *
 * A pause is not one thing: most are waits the platform resumes by itself, and only some need the
 * person. Recording which one it was is what lets a surface say "resumes automatically" instead of
 * offering a Resume that does nothing new. Meaningful only while the row is PAUSED.
 */
enum class DownloadPauseReason {
    /** The connection dropped mid-transfer. The download resumes when a network is back. */
    NETWORK_LOST,

    /**
     * The run was stopped before it finished: by Android (a time limit, a quota, Doze, a lost
     * constraint) or by the app replacing the work. It is scheduled again and resumes on its own.
     */
    INTERRUPTED,

    /** The server ended the session. The download resumes after the person signs in again. */
    SIGN_IN_REQUIRED,
}
