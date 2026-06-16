package com.calypsan.listenup.client.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.features.auth.components.AuthScaffold
import com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState
import com.calypsan.listenup.client.presentation.auth.PendingApprovalViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.auth_approved
import listenup.composeapp.generated.resources.auth_cancel_registration
import listenup.composeapp.generated.resources.auth_sign_in
import listenup.composeapp.generated.resources.auth_waiting_for_approval
import listenup.composeapp.generated.resources.auth_your_registration_request_has_been

/**
 * Screen shown while a registration waits for admin approval.
 *
 * Renders through the shared [AuthScaffold] so it matches the rest of the auth flow
 * (color-blocked hero + width-capped content, adaptive across phone / tablet / desktop).
 * The screen subscribes to the server-side approval-status stream (SSE); once approved,
 * the user signs in normally — there is no client-side auto-login. A denial is surfaced
 * via snackbar and routes back to login.
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
            snackbarHostState.showSnackbar(current.message)
            onNavigateToLogin()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PendingApprovalContent(
            state = state,
            email = viewModel.email,
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
    ) {
        EmailBadge(email)

        if (approved) {
            ListenUpButton(
                text = stringResource(Res.string.auth_sign_in),
                onClick = onSignIn,
                leadingIcon = Icons.AutoMirrored.Outlined.Login,
            )
        } else {
            Spacer(Modifier.height(4.dp))
            ListenUpLoadingIndicator()
            ListenUpButton(
                text = stringResource(Res.string.auth_cancel_registration),
                onClick = onCancel,
                filled = false,
            )
        }
    }
}

/** A rounded tertiary-container chip showing the email the request was made with. */
@Composable
private fun EmailBadge(email: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(50),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.MailOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = email,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
