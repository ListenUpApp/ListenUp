package com.calypsan.listenup.client.domain.usecase

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.PendingImageKind
import com.calypsan.listenup.client.domain.repository.PendingImageUploads
import com.calypsan.listenup.client.domain.repository.isWorthRetryingUpload

/**
 * What an edit screen's save achieved, once the save itself succeeded.
 *
 * A save has two halves: the metadata, which fails the whole save when it fails, and an optional
 * new cover or photo, which is written to the device first so it is never lost. The image
 * upload can fail while the save succeeds, so a bare success would let the screen say "done" about
 * something other devices will not see. This type carries that difference to the screen.
 */
sealed interface SaveOutcome {
    /** Everything the user changed is saved, and a new image, if there was one, reached the server. */
    data object Saved : SaveOutcome

    /**
     * The changes are saved and the new image is on this device, but the upload to the server failed
     * with [error]. [willRetry] says whether the upload is queued and will go up by itself. The screen
     * must say so; [toNotice] words it.
     */
    data class SavedImageNotUploaded(
        val error: AppError,
        val willRetry: Boolean,
    ) : SaveOutcome
}

/**
 * The notice to show for a save whose image did not upload: constant user-facing text, with the
 * upload's own failure kept as technical detail for the log. An upload that is queued says the image
 * will upload by itself; one that is not (refused, or on a platform that cannot queue) says it could
 * not be uploaded.
 */
internal fun SaveOutcome.SavedImageNotUploaded.toNotice(): AppError {
    val detail = "${error.code}: ${error.debugInfo ?: error.message}"
    return if (willRetry) {
        TransportError.ImageUploadQueued(correlationId = error.correlationId, debugInfo = detail)
    } else {
        TransportError.ImageNotUploaded(correlationId = error.correlationId, debugInfo = detail)
    }
}

/**
 * Settle a save's image upload into its [SaveOutcome], keeping the retry queue in step with it: an upload
 * that went through clears any older queued upload for the same image, and one that failed for a reason
 * worth retrying is queued so it goes up once the server answers.
 */
internal suspend fun PendingImageUploads.settle(
    kind: PendingImageKind,
    entityId: String,
    upload: AppResult<*>,
): SaveOutcome =
    when (upload) {
        is AppResult.Success -> {
            forget(kind, entityId)
            SaveOutcome.Saved
        }

        is AppResult.Failure -> {
            val queued = upload.error.isWorthRetryingUpload() && record(kind, entityId)
            SaveOutcome.SavedImageNotUploaded(upload.error, willRetry = queued)
        }
    }
