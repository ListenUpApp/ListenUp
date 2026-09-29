package com.calypsan.listenup.web.design

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Called with a name each time certain composables run their body — a seam for specs to count
 * recompositions of the parts whose recomposition is the cost being avoided (the shell's content
 * while playback ticks, a virtual list while it scrolls).
 *
 * A no-op in production. A count is the only honest way to assert "this did not recompose": the
 * DOM looks identical either way, which is exactly why the waste went unnoticed.
 */
val LocalCompositionProbe = staticCompositionLocalOf<(String) -> Unit> { {} }
