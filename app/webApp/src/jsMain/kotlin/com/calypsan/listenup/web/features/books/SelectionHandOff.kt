package com.calypsan.listenup.web.features.books

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.calypsan.listenup.web.nav.Route

/**
 * Carries a selection across a trip to the bulk editor and back, as Android's Library does: backing
 * out of the editor returns to the page with the same books still picked, and an applied edit ends
 * the selection (Android's `PendingSelectionExit`).
 *
 * Needed because a web page's multi-select session closes when its route does, so the picks would
 * otherwise be gone by the time the reader comes back.
 */
class SelectionHandOff {
    private var held: Pair<String, List<String>>? = null

    /** Remember [ids], picked on [origin], while the editor is open. */
    fun hold(
        origin: Route,
        ids: Collection<String>,
    ) {
        held = origin.toUrl() to ids.toList()
    }

    /** Where the held selection came from, so the editor can return there; null when nothing is held. */
    val origin: Route? get() = held?.first?.let(Route::parse)

    /** The held picks if they were made on [route], handed over once. */
    fun takeFor(route: Route): List<String>? {
        val (url, ids) = held ?: return null
        if (url != route.toUrl()) return null
        held = null
        return ids
    }

    /** The edit landed: the selection ends, and nothing is restored. */
    fun end() {
        held = null
    }
}

/** The window's [SelectionHandOff]; provided once by the shell, a fresh (inert) one elsewhere. */
val LocalSelectionHandOff: ProvidableCompositionLocal<SelectionHandOff> =
    staticCompositionLocalOf { SelectionHandOff() }
