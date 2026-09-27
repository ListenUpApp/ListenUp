package com.calypsan.listenup.client.design

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The widest a reading-width screen's content column grows (DESIGN.md → Layout). Settings, forms and
 * other single-column screens stop here on a tablet instead of stretching a phone column edge to edge.
 */
val ReadingMaxWidth = 640.dp

/**
 * Centres this element horizontally and caps it at [ReadingMaxWidth], filling the width below that.
 *
 * Chain it after the modifiers that should keep spanning the whole window — a `verticalScroll`, so
 * the gutters still scroll — and before the content's own padding. Below [ReadingMaxWidth] it is a
 * no-op, so a phone layout stays exactly as it was.
 */
fun Modifier.readingWidth(): Modifier =
    this
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = ReadingMaxWidth)
        .fillMaxWidth()
