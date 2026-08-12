package com.calypsan.listenup.client.features.auth.components

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
import listenup.composeapp.generated.resources.auth_reg_step_in_progress
import org.jetbrains.compose.resources.stringResource

/** Where a step sits in a waiting flow: already done, the one being waited on, or still ahead. */
internal enum class AuthStepState { DONE, ACTIVE, TODO }

/**
 * One step of a "what happens next" list, shared by the app's two waiting rooms.
 *
 * Registration and password reset both park the user while a human decides something, and both
 * explain the wait as an ordered list of steps. Rendering them from one component is what makes
 * the two read as the same app rather than two screens that happen to be waiting — which is the
 * whole reason the vocabulary is shared rather than copied.
 */
@Composable
internal fun AuthStepRow(
    state: AuthStepState,
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    val circleColor =
        when (state) {
            AuthStepState.DONE -> MaterialTheme.colorScheme.primary
            AuthStepState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
            AuthStepState.TODO -> MaterialTheme.colorScheme.surfaceVariant
        }
    val iconColor =
        when (state) {
            AuthStepState.DONE -> MaterialTheme.colorScheme.onPrimary
            AuthStepState.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
            AuthStepState.TODO -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(STEP_MARK_SIZE).clip(CircleShape).background(circleColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (state == AuthStepState.DONE) Icons.Rounded.Check else icon,
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
                if (state == AuthStepState.ACTIVE) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
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
