package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError

/** Which image a pending upload is for. The image's local file is named by this kind and its entity id. */
internal enum class PendingImageKind {
    /** A book's cover. */
    BookCover,

    /** A series' cover. */
    SeriesCover,

    /** A contributor's photo. */
    ContributorImage,
}

/**
 * The covers and photos saved on this device that have not reached the server yet, kept so the upload
 * is retried until the server answers.
 *
 * The image itself is the committed local file for the entity, not a copy held here: a second save for
 * the same image replaces the first, and the retry sends whatever file is there then. Only platforms
 * with a local image store record; the web client holds no image bytes after a save, so a failed upload
 * there is reported to the user and nothing is queued.
 */
internal interface PendingImageUploads {
    /**
     * Note that the image for ([kind], [entityId]) is saved locally but not on the server, so its upload
     * is retried. Returns whether it is now queued: false when this platform keeps no image file to
     * retry from, or no user is signed in to own the upload.
     */
    suspend fun record(
        kind: PendingImageKind,
        entityId: String,
    ): Boolean

    /** Forget a pending upload: the image reached the server some other way, so there is nothing to retry. */
    suspend fun forget(
        kind: PendingImageKind,
        entityId: String,
    )
}

/**
 * Whether retrying an upload that failed with this error can succeed without the user doing anything.
 *
 * True for the transient failures ([AppError.isRetryable]) and for two that look final but are not for
 * an image upload: a session that lapsed (it comes back when the user signs in again, and the retry
 * only runs while signed in) and an unknown outcome (the upload is a replace, so sending it twice is
 * harmless). Anything else, such as a permission refusal or an oversized file, is the server's answer.
 */
internal fun AppError.isWorthRetryingUpload(): Boolean =
    isRetryable || this is AuthError.SessionExpired || this is TransportError.OutcomeUnknown
