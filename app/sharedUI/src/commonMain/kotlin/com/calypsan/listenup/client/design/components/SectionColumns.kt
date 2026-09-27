package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.Spacing

/**
 * The narrowest a [SectionColumns] column is allowed to get — about a phone's content width, so a
 * [SectionGroup] of [SettingRow]s (tile, two-line text, trailing pill or switch) keeps the proportions
 * it was designed at.
 */
val SectionColumnMinWidth = 360.dp

/** Collects the sections a [SectionColumns] lays out, in reading order. */
class SectionColumnsScope internal constructor() {
    internal val sections = mutableListOf<@Composable () -> Unit>()

    /** Adds one section — typically a [SectionGroup], or a titled card — as a single masonry tile. */
    fun section(content: @Composable () -> Unit) {
        sections += content
    }
}

/**
 * Lays a screen's sections out as a masonry of balanced columns — the wide-window form of a settings
 * page or a sectioned form. The column count flows with the available width (as many
 * [minColumnWidth]-wide columns as fit), never exceeds the number of sections (two sections get two
 * half-width columns, not two thirds and an empty gutter), and each section drops into whichever
 * column is currently shortest, so uneven section heights still settle into an even block.
 *
 * Each section is its own traversal group, ordered by declaration: TalkBack reads a section through
 * before moving on, rather than zig-zagging across the columns row by row.
 *
 * Not lazy: it is for a screen's handful of sections, and it measures fine inside a `verticalScroll`.
 *
 * @param modifier Modifier for the whole block; it must be given a bounded width.
 * @param minColumnWidth The narrowest a column may be before the count drops by one.
 * @param horizontalSpacing Gutter between columns.
 * @param verticalSpacing Gap between sections stacked in the same column.
 * @param sections Declares the sections, in reading order, via [SectionColumnsScope.section].
 */
@Composable
fun SectionColumns(
    modifier: Modifier = Modifier,
    minColumnWidth: Dp = SectionColumnMinWidth,
    horizontalSpacing: Dp = Spacing.screenMargin,
    verticalSpacing: Dp = Spacing.sectionGap,
    sections: SectionColumnsScope.() -> Unit,
) {
    val declared = SectionColumnsScope().apply(sections).sections
    Layout(
        modifier = modifier.semantics { isTraversalGroup = true },
        content = {
            declared.forEachIndexed { index, section ->
                Box(
                    Modifier.semantics {
                        isTraversalGroup = true
                        traversalIndex = index.toFloat()
                    },
                ) { section() }
            }
        },
    ) { measurables, constraints ->
        val gutter = horizontalSpacing.roundToPx()
        val gap = verticalSpacing.roundToPx()
        val width = constraints.maxWidth
        val columnCount =
            ((width + gutter) / (minColumnWidth.roundToPx() + gutter))
                .coerceIn(1, measurables.size.coerceAtLeast(1))
        val columnWidth = ((width - gutter * (columnCount - 1)) / columnCount).coerceAtLeast(0)
        val columnHeights = IntArray(columnCount)
        val columnFilled = BooleanArray(columnCount)
        val placements =
            measurables.map { measurable ->
                val placeable =
                    measurable.measure(
                        Constraints(minWidth = columnWidth, maxWidth = columnWidth),
                    )
                val column = columnHeights.indices.minBy { columnHeights[it] }
                val y = columnHeights[column] + if (columnFilled[column]) gap else 0
                columnHeights[column] = y + placeable.height
                columnFilled[column] = true
                Triple(placeable, column * (columnWidth + gutter), y)
            }
        val height = (columnHeights.maxOrNull() ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            placements.forEach { (placeable, x, y) -> placeable.placeRelative(x, y) }
        }
    }
}
