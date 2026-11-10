package com.calypsan.listenup.client.presentation.edit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Read and write access to the form inside an edit screen's `Editing` state, for the collaborators
 * (delegates, the hierarchy editor) that edit one slice of it.
 *
 * Outside `Editing` there is no form: [value] is null, [flow] emits null, and [update] does nothing —
 * so a late reply can never resurrect an empty form over a load that failed.
 */
internal class FormSlot<S, F : Any>(
    private val state: MutableStateFlow<S>,
    private val formOf: (S) -> F?,
    private val withForm: (S, F) -> S,
) {
    /** The form being edited, or null when the screen is loading or failed to load. */
    val value: F? get() = formOf(state.value)

    /** [value] over time. */
    val flow: Flow<F?> = state.map(formOf)

    /** Replace the form with [transform] of it; a no-op when there is no form. */
    fun update(transform: (F) -> F) {
        state.update { current -> formOf(current)?.let { withForm(current, transform(it)) } ?: current }
    }
}
