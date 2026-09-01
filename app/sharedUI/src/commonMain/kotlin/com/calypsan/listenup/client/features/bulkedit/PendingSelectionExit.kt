package com.calypsan.listenup.client.features.bulkedit

/**
 * Carries "end the selection this editor was opened over" across the navigation between them.
 *
 * The multi-select ViewModel belongs to the screen the books were chosen on; the editor is a
 * different destination and cannot reach it. So the screen hands its own exit over when it opens
 * the editor, and the editor fires it if — and only if — an apply lands.
 *
 * It fires once. A second call does nothing, because the selection it named is already gone and the
 * screen may have armed a new one since.
 */
class PendingSelectionExit {
    private var endSelection: (() -> Unit)? = null

    /** Remembers how to end the selection the editor is about to open over. */
    fun arm(endSelection: () -> Unit) {
        this.endSelection = endSelection
    }

    /**
     * Ends that selection, once. A no-op when nothing is armed — which is the case whenever the
     * user left the editor without applying, and the selection is theirs to keep.
     */
    fun fireAndDisarm() {
        val end = endSelection ?: return
        endSelection = null
        end()
    }
}
