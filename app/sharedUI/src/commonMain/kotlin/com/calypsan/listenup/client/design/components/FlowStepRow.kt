package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import listenup.composeapp.generated.resources.Res
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import listenup.composeapp.generated.resources.auth_reg_step_in_progress
import listenup.composeapp.generated.resources.common_step_done
import listenup.composeapp.generated.resources.common_step_in_progress
import listenup.composeapp.generated.resources.common_step_not_started
import org.jetbrains.compose.resources.stringResource

/** Where a step sits in a multi-step flow: already done, the one under way, or still ahead. */
internal enum class FlowStepState { DONE, ACTIVE, TODO }

/**
 * One step of a "what happens next" list, shared by every flow that explains itself as steps.
 *
 * Registration and password reset park the user while a human decides something; restoring a backup
 * and uploading books walk an admin through a long operation ([FlowStepsPanel]). All of them explain
 * where they are as an ordered list of steps, and rendering them from one component is what makes
 * them read as the same app rather than screens that each drew their own progress — which is the
 * whole reason the vocabulary is shared rather than copied.
 */
@Composable
internal fun FlowStepRow(
    state: FlowStepState,
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    val circleColor =
        when (state) {
            FlowStepState.DONE -> MaterialTheme.colorScheme.primary
            FlowStepState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
            FlowStepState.TODO -> MaterialTheme.colorScheme.surfaceVariant
        }
    val stateLabel =
        stringResource(
            when (state) {
                FlowStepState.DONE -> Res.string.common_step_done
                FlowStepState.ACTIVE -> Res.string.common_step_in_progress
                FlowStepState.TODO -> Res.string.common_step_not_started
            },
        )
    val iconColor =
        when (state) {
            FlowStepState.DONE -> MaterialTheme.colorScheme.onPrimary
            FlowStepState.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
            FlowStepState.TODO -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // One step reads as one item, and says where it stands: the mark's colour and the
                // "In progress" pill are visual only.
                .semantics(mergeDescendants = true) { stateDescription = stateLabel }
                .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(STEP_MARK_SIZE).clip(CircleShape).background(circleColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (state == FlowStepState.DONE) Icons.Rounded.Check else icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(STEP_ICON_SIZE),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state == FlowStepState.ACTIVE) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        // The row's state description already says it; the pill would be read twice.
                        modifier = Modifier.clearAndSetSemantics {},
                    ) {
                        Text(
                            text = stringResource(Res.string.auth_reg_step_in_progress).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

private val STEP_MARK_SIZE = 34.dp

private val STEP_ICON_SIZE = 19.dp
