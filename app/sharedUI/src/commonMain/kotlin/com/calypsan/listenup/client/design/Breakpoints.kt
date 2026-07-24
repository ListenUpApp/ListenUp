package com.calypsan.listenup.client.design

import androidx.compose.ui.unit.dp

/**
 * Minimum available width at which a screen switches from the single-column layout to
 * a two-pane (brand panel + content) split. Below this, the single-column layout is used
 * (it centers/caps its content so it stays comfortable on wide single-column windows).
 *
 * Shared by every adaptive split screen (auth scaffold, library setup) so the breakpoint
 * is consistent app-wide. Set above the Material "expanded" floor (840dp) so the split only
 * engages when there's genuinely room for two comfortable columns — tablets, desktop, and
 * large/expanded foldables — rather than at the cramped low end of expanded.
 */
val TwoPaneMinWidth = 960.dp
