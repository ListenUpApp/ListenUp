package com.calypsan.listenup.client.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.features.auth.components.AuthBadge
import com.calypsan.listenup.client.features.auth.components.AuthScaffold
import com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState
import com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.auth_approved
import listenup.composeapp.generated.resources.auth_cancel_registration
import listenup.composeapp.generated.resources.auth_check_status
import listenup.composeapp.generated.resources.auth_checking_automatically
import listenup.composeapp.generated.resources.auth_pending_review
import listenup.composeapp.generated.resources.auth_reg_step_account_created
import listenup.composeapp.generated.resources.auth_reg_step_admin_approval
import listenup.composeapp.generated.resources.auth_reg_step_admin_approval_sub
import listenup.composeapp.generated.resources.auth_reg_step_in_progress
import listenup.composeapp.generated.resources.auth_reg_step_start_listening
import listenup.composeapp.generated.resources.auth_reg_step_start_listening_sub
import listenup.composeapp.generated.resources.auth_sign_in
import listenup.composeapp.generated.resources.auth_waiting_for_approval
import listenup.composeapp.generated.resources.auth_your_registration_request_has_been

/**
 * Screen shown while a registration waits for admin approval.
 *
 * Renders through the shared [AuthScaffold] so it matches the rest of the auth flow. The hero
 * carries a "Pending review" badge; the body reassures where the approval notice will land, shows
 * the registration as a three-step timeline, and offers a manual **Check Status** re-check
 * alongside the always-on status watch (never stranded). A denial is surfaced via snackbar and routes
 * back to login.
 */
@Composable
fun PendingApprovalScreen(
    viewModel: PendingApprovalViewModel,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state) {
        val current = state
        if (current is PendingApprovalUiState.Denied) {
            // Show the denial, then clear the pending registration — which flips AuthState back to
            // login and unmounts this screen. onNavigateToLogin remains as a belt-and-braces hook.
            snackbarHostState.showSnackbar(current.message)
            viewModel.cancelRegistration()
            onNavigateToLogin()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PendingApprovalContent(
            state = state,
            email = viewModel.email,
            onCheckStatus = viewModel::checkStatus,
            onSignIn = {
                viewModel.acknowledgeApproval()
                onNavigateToLogin()
            },
            onCancel = {
                viewModel.cancelRegistration()
                onNavigateToLogin()
            },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(16.dp),
        )
    }
}

/**
 * Stateless visual for the pending-approval screen — split out so it can be previewed and
 * screenshotted without a live [PendingApprovalViewModel].
 */
@Composable
internal fun PendingApprovalContent(
    state: PendingApprovalUiState,
    email: String,
    onCheckStatus: () -> Unit,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
) {
    val approved = state is PendingApprovalUiState.Approved
    AuthScaffold(
        title =
            stringResource(
                if (approved) Res.string.auth_approved else Res.string.auth_waiting_for_approval,
            ),
        subtitle = if (approved) null else stringResource(Res.string.auth_your_registration_request_has_been),
        badge =
            if (approved) {
                null
            } else {
                AuthBadge(
                    Icons.Outlined.Schedule,
                    stringResource(Res.string.auth_pending_review),
                )
            },
    ) {
        if (approved) {
            ListenUpButton(
                text = stringResource(Res.string.auth_sign_in),
                onClick = onSignIn,
                leadingIcon = Icons.AutoMirrored.Outlined.Login,
            )
        } else {
            RegistrationTimeline(email)
            AutoCheckRow()
            Spacer(Modifier.height(4.dp))
            ListenUpButton(
                text = stringResource(Res.string.auth_check_status),
                onClick = onCheckStatus,
                leadingIcon = Icons.Outlined.Refresh,
            )
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = stringResource(Res.string.auth_cancel_registration),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** The registration progress as a three-step timeline. */
@Composable
private fun RegistrationTimeline(email: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            RegStepRow(
                state = StepState.DONE,
                icon = Icons.Outlined.Person,
                title = stringResource(Res.string.auth_reg_step_account_created),
                subtitle = email,
            )
            RegStepRow(
                state = StepState.ACTIVE,
                icon = Icons.Outlined.Shield,
                title = stringResource(Res.string.auth_reg_step_admin_approval),
                subtitle = stringResource(Res.string.auth_reg_step_admin_approval_sub),
            )
            RegStepRow(
                state = StepState.TODO,
                icon = Icons.Outlined.Headphones,
                title = stringResource(Res.string.auth_reg_step_start_listening),
                subtitle = stringResource(Res.string.auth_reg_step_start_listening_sub),
            )
        }
    }
}

private enum class StepState { DONE, ACTIVE, TODO }

@Composable
private fun RegStepRow(
    state: StepState,
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    val circleColor =
        when (state) {
            StepState.DONE -> MaterialTheme.colorScheme.primary
            StepState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
            StepState.TODO -> MaterialTheme.colorScheme.surfaceVariant
        }
    val iconColor =
        when (state) {
            StepState.DONE -> MaterialTheme.colorScheme.onPrimary
            StepState.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
            StepState.TODO -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape).background(circleColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (state == StepState.DONE) Icons.Rounded.Check else icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state == StepState.ACTIVE) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
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

/** Subtle "we're watching for you" status line — honest: the status watch is live while this shows. */
@Composable
private fun AutoCheckRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(Res.string.auth_checking_automatically),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
