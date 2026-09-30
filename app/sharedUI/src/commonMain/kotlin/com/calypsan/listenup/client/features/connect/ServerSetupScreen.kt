package com.calypsan.listenup.client.features.connect

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.features.auth.components.AuthHelperCard
import com.calypsan.listenup.client.features.auth.components.AuthScaffold
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.client.features.permission.LocalNetworkPermissionRecovery
import com.calypsan.listenup.client.features.permission.LocalNetworkRecoveryAction
import com.calypsan.listenup.client.features.permission.rememberLocalNetworkPermissionRecovery
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.connect.ServerConnectUiState
import com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.connect_connect
import listenup.composeapp.generated.resources.connect_connect_to_server
import listenup.composeapp.generated.resources.connect_connect_to_server_subtitle
import listenup.composeapp.generated.resources.connect_local_network_allow
import listenup.composeapp.generated.resources.connect_local_network_hint_android
import listenup.composeapp.generated.resources.connect_local_network_open_settings
import listenup.composeapp.generated.resources.connect_server_url
import listenup.composeapp.generated.resources.connect_server_url_hint
import listenup.composeapp.generated.resources.connect_server_url_placeholder

/**
 * Manual server-URL entry — reached from server selection when discovery doesn't surface the
 * target. Renders through the shared [AuthScaffold]; the hero back affordance pops to selection.
 *
 * @param onServerVerified Invoked once the entered URL is verified and saved.
 * @param onBack Pops back to server selection; null hides the back affordance.
 */
@Composable
fun ServerSetupScreen(
    onServerVerified: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: ServerConnectViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var serverUrl by rememberSaveable { mutableStateOf("") }
    val localNetwork = rememberLocalNetworkPermissionRecovery()

    LaunchedEffect(state) {
        if (state is ServerConnectUiState.Verified) {
            onServerVerified()
        }
    }

    // Returning from the permission dialog or from Settings with access granted re-runs the
    // attempt the denial blocked — the user should not have to press Connect a second time.
    LaunchedEffect(localNetwork.isGranted) {
        if (localNetwork.isGranted) viewModel.retryAfterLocalNetworkGrant()
    }

    ServerSetupContent(
        state = state,
        serverUrl = serverUrl,
        onServerUrlChange = {
            serverUrl = it
            viewModel.clearError()
        },
        onConnect = { viewModel.submitUrl(serverUrl) },
        localNetwork = localNetwork,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Stateless body of [ServerSetupScreen].
 *
 * @param localNetwork The local network permission and the action that restores it.
 */
@Composable
internal fun ServerSetupContent(
    state: ServerConnectUiState,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    localNetwork: LocalNetworkPermissionRecovery,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AuthScaffold(
            title = stringResource(Res.string.connect_connect_to_server),
            subtitle = stringResource(Res.string.connect_connect_to_server_subtitle),
            onBack = onBack,
        ) {
            val isVerifying = state is ServerConnectUiState.Verifying
            val errorState = state as? ServerConnectUiState.Error
            val blockedByPermission = errorState?.error is ServerConnectError.LocalNetworkPermissionDenied

            ListenUpTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = stringResource(Res.string.connect_server_url),
                placeholder = stringResource(Res.string.connect_server_url_placeholder),
                isError = errorState != null,
                // The permission card below says it with the fix attached; don't say it twice.
                supportingText = errorState?.error?.takeUnless { blockedByPermission }?.localized(),
                leadingIcon = Icons.Outlined.Link,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { onConnect() }),
            )

            // Shown up front whenever the permission is missing, not only after a failed connect:
            // on Android 17 every LAN address will fail without it.
            if (!localNetwork.isGranted || blockedByPermission) {
                LocalNetworkAccessCard(localNetwork)
            }

            AuthHelperCard(
                icon = Icons.Outlined.Lightbulb,
                text = stringResource(Res.string.connect_server_url_hint),
            )

            ListenUpButton(
                text = stringResource(Res.string.connect_connect),
                onClick = onConnect,
                leadingIcon = Icons.Outlined.CloudDone,
                isLoading = isVerifying,
                enabled = serverUrl.isNotBlank() && !isVerifying,
            )
        }
    }
}

/**
 * Explains that local network access is off and carries the action that turns it back on: the
 * permission dialog while the system will still show it, the app's Settings page once it won't.
 */
@Composable
private fun LocalNetworkAccessCard(localNetwork: LocalNetworkPermissionRecovery) {
    val explanation = ServerConnectError.LocalNetworkPermissionDenied().localized()
    val opensSettings = localNetwork.action == LocalNetworkRecoveryAction.OpenSettings
    AuthHelperCard(
        icon = Icons.Outlined.WifiOff,
        text = explanation,
        action = {
            if (opensSettings) {
                // Settings opens on the app's page, not the permission — say where to go from there.
                Text(
                    text = stringResource(Res.string.connect_local_network_hint_android),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ListenUpButton(
                text =
                    stringResource(
                        if (opensSettings) Res.string.connect_local_network_open_settings else Res.string.connect_local_network_allow,
                    ),
                onClick = localNetwork.recover,
                filled = false,
                fillMaxWidth = false,
            )
        },
    )
}
