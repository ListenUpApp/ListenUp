package com.calypsan.listenup.client.download

/**
 * Why a download run stopped before its book finished, in one vocabulary for every platform. Android maps the
 * platform's stop codes onto it (the raw code is recorded beside it); the app's own stops (a pause, a cancel, a
 * preference change) name themselves. Recorded on the row and in the bounded stop history, which is what a doctor and
 * Share logs read to say why a download did not keep going.
 */
internal enum class DownloadStopReason {
    /** The system's execution limit for the job ran out. */
    SYSTEM_TIMEOUT,

    /** The app's job quota for its standby bucket ran out. */
    QUOTA,

    /** App standby held the job back. */
    APP_STANDBY,

    /** Doze, battery saver or memory pressure stopped it. */
    DEVICE_STATE,

    /** The person restricted ListenUp's background use. */
    BACKGROUND_RESTRICTION,

    /** The network the job requires went away (or Wi-Fi-only lost its Wi-Fi). */
    NETWORK_CONSTRAINT,

    /** Storage ran low. */
    STORAGE_CONSTRAINT,

    /** Another constraint (charging, battery, idle) stopped being met. */
    OTHER_CONSTRAINT,

    /** The system made room for more important work. */
    PREEMPT,

    /** The system stopped it for its own processing. */
    SYSTEM_PROCESSING,

    /** The foreground service's daily time budget ran out (Android 15+ `dataSync`). */
    FGS_TIMEOUT,

    /** The person stopped ListenUp from the system's Task Manager or settings. */
    USER_STOPPED_FROM_SYSTEM,

    /** The app cancelled the job for a reason it did not name. */
    CANCELLED_BY_APP,

    /** The person paused the download. */
    USER_PAUSED,

    /** The person cancelled the download. */
    USER_CANCELLED,

    /** The Wi-Fi-only preference changed and the job was rescheduled under the new rule. */
    PREFERENCE_CHANGED,

    /** The server ended the session. */
    AUTH_EXPIRED,

    /** A read failed because the connection dropped. */
    NETWORK_LOST,

    /** The process died mid-transfer and the row was found still downloading at the next start. */
    PROCESS_DEATH,

    /** The platform refused to schedule the job (on Android, scheduling outside the visible window). */
    SCHEDULE_REFUSED,

    /** The platform cannot say why (Android 11 has no stop-reason API). */
    UNAVAILABLE_ON_API_30,

    /** A code this build does not recognise. */
    UNKNOWN,
    ;

    /** Whether the download carries on by itself after this stop, without the person doing anything. */
    val resumesByItself: Boolean
        get() =
            when (this) {
                USER_PAUSED, USER_CANCELLED, USER_STOPPED_FROM_SYSTEM, CANCELLED_BY_APP, AUTH_EXPIRED -> false

                SYSTEM_TIMEOUT, QUOTA, APP_STANDBY, DEVICE_STATE, BACKGROUND_RESTRICTION, NETWORK_CONSTRAINT,
                STORAGE_CONSTRAINT, OTHER_CONSTRAINT, PREEMPT, SYSTEM_PROCESSING, FGS_TIMEOUT, PREFERENCE_CHANGED,
                NETWORK_LOST, PROCESS_DEATH, SCHEDULE_REFUSED, UNAVAILABLE_ON_API_30, UNKNOWN,
                -> true
            }
}

/** The transport that ran a download, recorded with every stop. */
internal enum class DownloadTransport {
    /** A user-initiated data transfer job (Android 14+). */
    UIDT,

    /** A WorkManager worker running as a `dataSync` foreground service (Android 11–13). */
    WORKMANAGER_FGS,

    /** A WorkManager worker the platform would not promote to the foreground (started in the background). */
    WORKMANAGER_PLAIN,
}
