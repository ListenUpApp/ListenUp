package com.calypsan.listenup.client.foldable

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntRect

/** Folded posture — drives layout decisions on hinged devices. */
enum class Posture { NORMAL, TABLETOP, BOOK }

/**
 * The window's fold: its [posture], and where the hinge lies.
 *
 * [hingeBounds] is in window pixels — the hinge's position and thickness as the window sees it — or
 * null when the window has no fold. A hinge can be zero-thick (a seamless fold reports a line), so a
 * layout that splits at it keeps its own margin either side rather than relying on the thickness.
 */
@Immutable
data class Fold(
    val posture: Posture,
    val hingeBounds: IntRect?,
) {
    /** The fold-free default. */
    companion object {
        /** No fold: a slab phone, a tablet, desktop, or anything outside a [PostureProvider]. */
        val None = Fold(Posture.NORMAL, hingeBounds = null)
    }
}

/**
 * Composition local exposing the window's [Fold]. Defaults to [Fold.None] outside a `PostureProvider`
 * scope, which is every desktop window.
 *
 * Declared `static` because the fold is provided once at the root and changes rarely (only on a
 * physical fold or unfold) — a cheap read at every consumer outweighs the full-subtree recomposition
 * on the infrequent change.
 */
val LocalFold = staticCompositionLocalOf { Fold.None }
