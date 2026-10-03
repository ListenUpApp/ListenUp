package com.calypsan.listenup.client.design.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity

/**
 * The font scale from which a layout trades decoration and pinned chrome for room to read: a decorative
 * leading tile steps aside, pinned headers and footers scroll with the content, a top-bar title may take a
 * second line. Android's "Large" step is about 1.15 and its largest is 2.0; past 1.3 a phone column runs out
 * of width for whole words.
 */
internal const val LARGE_FONT_SCALE = 1.3f

/** Whether the person reads at [LARGE_FONT_SCALE] or more. */
@Composable
@ReadOnlyComposable
internal fun isLargeFontScale(): Boolean = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
