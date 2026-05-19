package com.calypsan.listenup.client.foldable

import androidx.compose.runtime.staticCompositionLocalOf

/** Folded posture — drives layout decisions on hinged devices. */
enum class Posture { NORMAL, TABLETOP, BOOK }

/**
 * Composition local exposing the current device posture. Defaults to [Posture.NORMAL]
 * outside a [PostureProvider] scope.
 *
 * Declared `static` because posture is provided once at the root and changes rarely
 * (only on physical fold/unfold) — a cheap read at every consumer outweighs the
 * full-subtree recomposition on the infrequent change.
 */
val LocalPosture = staticCompositionLocalOf { Posture.NORMAL }
