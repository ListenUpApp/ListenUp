package com.calypsan.listenup.client.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BrandLogo
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
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
 * Screen shown while waiting for admin approval after registration.
 *
 * The screen subscribes to the server-side approval-status stream (SSE with
 * polling fallback). Once approved, the user logs in normally — there is no
 * client-side auto-login.
 */
@Composable
fun PendingApprovalScreen(
    viewModel: PendingApprovalViewModel,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface denial via snackbar then route the user back to login.
    LaunchedEffect(state) {
        val current = state
        if (current is PendingApprovalUiState.Denied) {
            snackbarHostState.showSnackbar(current.message)
            onNavigateToLogin()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandLogo(size = 120.dp)

            Spacer(modifier = Modifier.height(24.dp))

            ElevatedCard(
                modifier =
                    Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors =
                    CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    when (val current = state) {
                        PendingApprovalUiState.Waiting -> {
                            ListenUpLoadingIndicator()

                            Text(
                                text = stringResource(Res.string.auth_waiting_for_approval),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            Text(
                                text = stringResource(Res.string.auth_your_registration_request_has_been),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )

                            Text(
                                text = viewModel.email,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        PendingApprovalUiState.Approved -> {
                            Text(
                                text = stringResource(Res.string.auth_approved),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )

                            Button(
                                onClick = {
                                    viewModel.acknowledgeApproval()
                                    onNavigateToLogin()
                                },
                            ) {
                                Text(stringResource(Res.string.auth_sign_in))
                            }
                        }

                        is PendingApprovalUiState.Denied -> {
                            // Handled via snackbar + navigation in LaunchedEffect above.
                            @Suppress("UnusedExpression")
                            current
                        }
                    }

                    if (state == PendingApprovalUiState.Waiting) {
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                viewModel.cancelRegistration()
                                onNavigateToLogin()
                            },
                        ) {
                            Text(stringResource(Res.string.auth_cancel_registration))
                        }
                    }
                }
            }
        }
    }
}
