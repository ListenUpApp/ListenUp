package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.theme.Spacing

/**
 * One step of a multi-step flow, as a [FlowStepsPanel] lists it.
 *
 * @property icon The step's glyph while it is under way or still ahead (a done step shows a check).
 * @property title The step's short name.
 * @property subtitle One line on what happens in the step.
 */
@Immutable
data class FlowStep(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
)

/** Width of a wide flow's steps panel — one comfortable card column. */
val FlowStepsPanelWidth = 360.dp

/** The narrowest a flow's action gets in the wide working pane, where it no longer spans the column. */
val FlowActionMinWidth = 280.dp

/**
 * Lays out one screen of a multi-step flow — restoring a backup, uploading books — for the window.
 *
 * A phone gets [content] alone, exactly as the screen draws it. From the medium width up, [steps]
 * sit in a [FlowStepsPanel] beside [content], so a long or destructive operation shows where it
 * started and where it is heading while the working pane does the step itself.
 *
 * @param steps The flow's steps, in order.
 * @param currentStep Index of the step under way; earlier steps read as done, later ones as ahead.
 *   [steps]`.size` marks the whole flow finished.
 * @param modifier Modifier for the whole layout — typically the scaffold's padding.
 * @param content The working pane, given whether the layout is wide and the modifier to apply.
 */
@Composable
fun FlowWithSteps(
    steps: List<FlowStep>,
    currentStep: Int,
    modifier: Modifier = Modifier,
    content: @Composable (isWide: Boolean, modifier: Modifier) -> Unit,
) {
    val isWide =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
    if (!isWide) {
        content(false, modifier)
        return
    }
    Row(
        modifier = modifier.fillMaxSize().padding(start = Spacing.screenMargin),
        // The working pane pads its own content, so a small gap here reads as a section gap.
        horizontalArrangement = Arrangement.spacedBy(Spacing.titleGap),
    ) {
        FlowStepsPanel(
            steps = steps,
            currentStep = currentStep,
            modifier =
                Modifier
                    .width(FlowStepsPanelWidth)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
        )
        content(true, Modifier.weight(1f))
    }
}

/**
 * A flow's steps as a card of [FlowStepRow]s: done steps checked, the current one marked in
 * progress, the rest ahead.
 */
@Composable
fun FlowStepsPanel(
    steps: List<FlowStep>,
    currentStep: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                steps.forEachIndexed { index, step ->
                    FlowStepRow(
                        state =
                            when {
                                index < currentStep -> FlowStepState.DONE
                                index == currentStep -> FlowStepState.ACTIVE
                                else -> FlowStepState.TODO
                            },
                        icon = step.icon,
                        title = step.title,
                        subtitle = step.subtitle,
                    )
                }
            }
        }
    }
}

/**
 * A flow action's width: the whole column on a phone, and its own width — never under
 * [FlowActionMinWidth] — in a wide flow's working pane, where a full-width button would run the
 * length of the window.
 */
fun Modifier.flowActionWidth(isWide: Boolean): Modifier =
    if (isWide) widthIn(min = FlowActionMinWidth) else fillMaxWidth()
