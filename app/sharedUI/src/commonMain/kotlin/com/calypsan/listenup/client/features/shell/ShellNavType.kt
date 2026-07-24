package com.calypsan.listenup.client.features.shell

import androidx.window.core.layout.WindowSizeClass

/**
 * Which adaptive navigation surface the shell renders at the current window size.
 *
 * - [BottomBar] — compact widths (phones): an expressive `ShortNavigationBar`.
 * - [RailCollapsed] — medium widths (tablet portrait, foldables): an icon-only `WideNavigationRail`.
 * - [RailExpanded] — expanded widths (landscape tablet, desktop): an icon + label `WideNavigationRail`.
 */
enum class ShellNavType { BottomBar, RailCollapsed, RailExpanded }

/**
 * Maps a [WindowSizeClass] to the shell's [ShellNavType].
 *
 * Uses a custom 1000dp expanded threshold (rather than the standard 840dp) so foldables
 * such as the Pixel Fold (~930dp) stay on the collapsed rail instead of the expanded form.
 */
fun shellNavType(windowSizeClass: WindowSizeClass): ShellNavType {
    val isExpanded = windowSizeClass.isWidthAtLeastBreakpoint(EXPANDED_WIDTH_THRESHOLD_DP)
    val isMedium =
        !isExpanded &&
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    return when {
        isExpanded -> ShellNavType.RailExpanded
        isMedium -> ShellNavType.RailCollapsed
        else -> ShellNavType.BottomBar
    }
}

/** Keep foldables (Pixel Fold ~930dp) on the rail rather than the expanded form. */
private const val EXPANDED_WIDTH_THRESHOLD_DP = 1000
