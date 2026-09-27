package com.calypsan.listenup.client.design

import androidx.compose.ui.unit.dp

/**
 * The widest a run of prose, a card or a progress bar grows *inside* a pane (DESIGN.md → Layout), so
 * lines stay a readable length and a bar does not stretch into a hairline across a tablet's working
 * pane. Apply it as `Modifier.widthIn(max = ReadableMeasure)` on the element itself.
 *
 * It is for limiting line length within a pane — never for centring a whole screen in a capped
 * column. A tablet screen earns a real wide layout instead: a side panel,
 * [com.calypsan.listenup.client.design.components.SectionColumns], or an adaptive grid
 * (`app/sharedUI/CLAUDE.md` rule 11).
 */
val ReadableMeasure = 640.dp
