package com.calypsan.listenup.client.presentation.edit

/**
 * What an edit screen's form is busy with — the one shape the Book, Contributor and Series editors
 * share for "is something in flight?".
 *
 * Mutually exclusive by construction: Save, a merge and a photo being staged can never overlap,
 * because each starts only from [Idle]. That is what refuses a second tap on Save while the first is
 * still on its way — the double submit is not representable, rather than guarded at each call site.
 */
sealed interface EditPhase {
    /** Nothing in flight; the form accepts Save, a merge or a new image. */
    data object Idle : EditPhase

    /** The edits are on their way to the server. */
    data object Saving : EditPhase

    /** A picked image is being written to its local staging file for preview. */
    data object StagingImage : EditPhase

    /** A merge is on its way to the server. */
    data object Merging : EditPhase
}
