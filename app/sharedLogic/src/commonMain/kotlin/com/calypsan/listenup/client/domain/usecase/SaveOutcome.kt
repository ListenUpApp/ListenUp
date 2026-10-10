package com.calypsan.listenup.client.domain.usecase

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError

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
     * with [error]. The screen must say so.
     */
    data class SavedImageNotUploaded(
        val error: AppError,
    ) : SaveOutcome
}

/**
 * The notice to show for a save whose image did not upload: constant user-facing text, with the
 * upload's own failure kept as technical detail for the log.
 */
fun SaveOutcome.SavedImageNotUploaded.toNotice(): AppError =
    TransportError.ImageNotUploaded(
        correlationId = error.correlationId,
        debugInfo = "${error.code}: ${error.debugInfo ?: error.message}",
    )
