@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.features.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BrandLogo
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.presentation.connect.ServerConnectUiState
import com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.connect_back_to_server_list
import listenup.composeapp.generated.resources.connect_connect
import listenup.composeapp.generated.resources.connect_connect_to_server
import listenup.composeapp.generated.resources.connect_server_url_placeholder
import listenup.composeapp.generated.resources.connect_server_url

/**
 * Server setup screen with adaptive layout.
 *
 * Features:
 * - Side-by-side layout on wide screens (landscape/tablet/TV)
 * - Vertical card layout on narrow screens (portrait phone)
 * - Scaffold for proper Material 3 structure
 * - Respects system theme and dynamic colors
 * - Edge-to-edge with proper insets
 *
 * @param onServerVerified Callback when server is successfully verified
 * @param modifier Modifier for the root composable
 */
@Composable
fun ServerSetupScreen(
    onServerVerified: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: ServerConnectViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var serverUrl by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is ServerConnectUiState.Verified) {
            onServerVerified()
        }
    }

    ServerSetupContent(
        state = state,
        serverUrl = serverUrl,
        onUrlChanged = { newUrl ->
            serverUrl = newUrl
            viewModel.clearError()
        },
        onSubmit = { viewModel.submitUrl(serverUrl) },
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Stateless content for ServerSetupScreen.
 */
@Composable
private fun ServerSetupContent(
    state: ServerConnectUiState,
    serverUrl: String,
    onUrlChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val useWideLayout =
        windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        if (useWideLayout) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
            ) {
                // Left pane: logo
                Box(
                    modifier =
                        Modifier
                            .weight(0.4f)
                            .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    BrandLogo()
                }

                // Right pane: form
                Box(
                    modifier =
                        Modifier
                            .weight(0.6f)
                            .fillMaxHeight()
                            .imePadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 32.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FormContent(
                            state = state,
                            serverUrl = serverUrl,
                            onUrlChanged = onUrlChanged,
                            onSubmit = onSubmit,
                            modifier = Modifier.widthIn(max = 480.dp),
                        )

                        if (onBack != null) {
                            Spacer(modifier = Modifier.height(16.dp))

                            TextButton(onClick = onBack) {
                                Text(stringResource(Res.string.connect_back_to_server_list))
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Top spacing
                Spacer(modifier = Modifier.height(24.dp))

                // Logo
                BrandLogo()

                // Gap between logo and card
                Spacer(modifier = Modifier.height(24.dp))

                // Form card - constrained width for tablets
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
                    FormContent(
                        state = state,
                        serverUrl = serverUrl,
                        onUrlChanged = onUrlChanged,
                        onSubmit = onSubmit,
                        modifier = Modifier.padding(24.dp),
                    )
                }

                // Back button (only shown when there's somewhere to go back to)
                if (onBack != null) {
                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(onClick = onBack) {
                        Text(stringResource(Res.string.connect_back_to_server_list))
                    }
                }

                // Bottom spacing
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/**
 * Form content inside the card.
 * Contains title, text field, and connect button.
 */
@Composable
private fun FormContent(
    state: ServerConnectUiState,
    serverUrl: String,
    onUrlChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isVerifying = state is ServerConnectUiState.Verifying
    val errorState = state as? ServerConnectUiState.Error

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Title
        Text(
            text = stringResource(Res.string.connect_connect_to_server),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // URL input field
        ListenUpTextField(
            value = serverUrl,
            onValueChange = onUrlChanged,
            label = stringResource(Res.string.connect_server_url),
            placeholder = stringResource(Res.string.connect_server_url_placeholder),
            isError = errorState != null,
            supportingText = errorState?.error?.message,
            keyboardOptions =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )

        // Connect button
        ListenUpButton(
            text = stringResource(Res.string.connect_connect),
            onClick = onSubmit,
            isLoading = isVerifying,
            enabled = serverUrl.isNotBlank() && !isVerifying,
        )
    }
}
