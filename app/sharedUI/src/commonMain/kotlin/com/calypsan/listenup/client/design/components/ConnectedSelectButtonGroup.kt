package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One choice in a [ConnectedSelectButtonGroup].
 *
 * @property value What choosing it selects.
 * @property label The short text the button shows.
 * @property accessibleLabel What a screen reader hears instead, when the short text alone is ambiguous.
 */
data class ButtonGroupChoice<T>(
    val value: T,
    val label: String,
    val accessibleLabel: String = label,
)

/**
 * A single-select Material 3 Expressive connected button group — the real M3 [ToggleButton]s with
 * [ButtonGroupDefaults]' connected leading / middle / trailing shapes, spaced by
 * [ButtonGroupDefaults.ConnectedSpaceBetween], laid out the way M3's single-select connected sample
 * lays them out. The selected button's morph to fully round, its colours and its motion are the
 * component's own ([androidx.compose.material3.ToggleButtonShapes]).
 *
 * Widths: every button gets its label's width first, then an even share of the space left over, so a
 * long label ("12 months") is never squeezed to make the short ones match. Once every label fits in an
 * equal share with room to spare ([EQUAL_WIDTH_SIDE_ROOM] per side), the buttons are equal instead.
 * The row never grows past [maxWidth]. When the labels can't share one row — at a large font scale —
 * the group wraps into connected pairs. Widths never animate.
 *
 * Accessibility: the group is a [selectableGroup] named [groupLabel]; each button is a radio button
 * named by its [ButtonGroupChoice.accessibleLabel].
 *
 * @param choices The options, in display order.
 * @param selected The value of the selected choice.
 * @param onSelect Called with a choice's value when it is chosen.
 * @param groupLabel What a screen reader calls the whole group.
 * @param modifier Modifier for the group.
 * @param maxWidth The widest the row may grow.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ConnectedSelectButtonGroup(
    choices: List<ButtonGroupChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    groupLabel: String,
    modifier: Modifier = Modifier,
    maxWidth: Dp = DEFAULT_MAX_WIDTH,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val rowWidth = minOf(this.maxWidth, maxWidth)
        val spacing = ButtonGroupDefaults.ConnectedSpaceBetween
        // Each button's content width: its label (as the ToggleButton will set it) plus its padding.
        val contentWidths =
            choices.map { choice ->
                val labelPx =
                    measurer
                        .measure(text = choice.label, style = labelStyle, maxLines = 1, softWrap = false)
                        .size
                        .width
                with(density) { labelPx.toDp() } + BUTTON_SIDE_PADDING * 2
            }
        val rows = rowsFor(contentWidths, rowWidth, spacing)

        Column(
            modifier =
                Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .selectableGroup()
                    .semantics { contentDescription = groupLabel },
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            rows.forEach { indices ->
                val rowContent = indices.map { contentWidths[it] }
                val equal = equalWidthsFit(rowContent, rowWidth, spacing)
                val share =
                    (rowWidth - spacing * (indices.size - 1) - rowContent.fold(0.dp) { a, b -> a + b }) / indices.size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    indices.forEachIndexed { position, index ->
                        val choice = choices[index]
                        // Weights proportional to each button's target width (its content plus an even
                        // share), so the row splits exactly as intended with no rounding overflow.
                        val weight = if (equal) 1f else (contentWidths[index] + share).value
                        ToggleButton(
                            checked = choice.value == selected,
                            onCheckedChange = { onSelect(choice.value) },
                            shapes =
                                when (position) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    indices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                            contentPadding = PaddingValues(horizontal = BUTTON_SIDE_PADDING),
                            modifier =
                                Modifier.weight(weight).semantics {
                                    role = Role.RadioButton
                                    contentDescription = choice.accessibleLabel
                                },
                        ) {
                            Text(text = choice.label, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

/** The rows the choices fall into: all on one row when their content fits, else connected pairs. */
private fun rowsFor(
    contentWidths: List<Dp>,
    rowWidth: Dp,
    spacing: Dp,
): List<List<Int>> {
    val oneRow = contentWidths.fold(0.dp) { a, b -> a + b } + spacing * (contentWidths.size - 1)
    val indices = contentWidths.indices.toList()
    return if (oneRow <= rowWidth) listOf(indices) else indices.chunked(PAIR)
}

/** Whether every button in a row can take an equal share and still leave [EQUAL_WIDTH_SIDE_ROOM] per side. */
private fun equalWidthsFit(
    rowContent: List<Dp>,
    rowWidth: Dp,
    spacing: Dp,
): Boolean {
    val equalShare = (rowWidth - spacing * (rowContent.size - 1)) / rowContent.size
    val widestLabel = rowContent.maxOf { it } - BUTTON_SIDE_PADDING * 2
    return widestLabel + EQUAL_WIDTH_SIDE_ROOM * 2 <= equalShare
}

/** The least padding beside a label; the even share of spare width adds to it. */
private val BUTTON_SIDE_PADDING = 4.dp

/** The room per side every label needs before the buttons switch to equal widths. */
private val EQUAL_WIDTH_SIDE_ROOM = 16.dp

/** The widest the group grows on a wide window. */
private val DEFAULT_MAX_WIDTH = 480.dp

/** Choices per row once the group wraps. */
private const val PAIR = 2
