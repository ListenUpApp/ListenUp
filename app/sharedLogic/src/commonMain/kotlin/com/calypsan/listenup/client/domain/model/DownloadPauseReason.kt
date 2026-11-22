package com.calypsan.listenup.client.domain.model

/**
 * Why a download row is waiting.
 *
 * A pause is not one thing: most are waits the platform resumes by itself, and only some need the
 * person. Recording which one it was is what lets a surface say "resumes automatically" instead of
 * offering a Resume that does nothing new. Meaningful while the row is [DownloadStatus.PAUSED], and
 * for the two reasons a [DownloadStatus.QUEUED] row can carry: [APP_CLOSED] and [URL_EXPIRED].
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

    /** The person paused it. It stays paused, its progress kept, until they resume it. */
    PAUSED_BY_YOU,

    /**
     * ListenUp was swiped away mid-download, which stops an iOS background transfer. The row is back
     * in the queue and resuming from where it stopped; it says so until bytes move again.
     */
    APP_CLOSED,

    /**
     * The server refused the transfer's signed URL because it had expired. The row is back in the
     * queue for a fresh URL; no retry is spent, because nothing about the file failed.
     */
    URL_EXPIRED,
}
