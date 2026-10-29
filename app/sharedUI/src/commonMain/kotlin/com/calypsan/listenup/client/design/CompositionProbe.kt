package com.calypsan.listenup.client.design

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Called with a name each time certain composables run their body: a seam for specs to count the recompositions of
 * the parts whose recomposition is the cost being avoided (Now Playing, the bar and the shell's bottom bar while the
 * mini player morphs). A no-op in production. A count is the only honest way to assert "this did not recompose": the
 * screen looks the same either way.
 */
internal val LocalCompositionProbe = staticCompositionLocalOf<(String) -> Unit> { {} }
