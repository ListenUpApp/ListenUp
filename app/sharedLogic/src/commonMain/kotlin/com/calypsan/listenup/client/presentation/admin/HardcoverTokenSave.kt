package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.AppError

/**
 * Where Admin → Hardcover's API token field stands (#1542). The token itself is never here: it lives
 * only in the field until Save sends it, once.
 */
sealed interface HardcoverTokenSave {
    /** Nothing in flight. */
    data object Idle : HardcoverTokenSave

    /** A token is being checked with Hardcover, or removed. */
    data object Busy : HardcoverTokenSave

    /** The last save was refused; [error] is shown beside the field until the admin edits it. */
    data class Refused(
        val error: AppError,
    ) : HardcoverTokenSave
}
