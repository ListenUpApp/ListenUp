package com.calypsan.listenup.client.features.connect

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
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
import com.calypsan.listenup.client.presentation.connect.ServerConnectUiState
import com.calypsan.listenup.client.presentation.connect.ServerConnectViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.connect_connect
import listenup.composeapp.generated.resources.connect_connect_to_server
import listenup.composeapp.generated.resources.connect_connect_to_server_subtitle
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

    LaunchedEffect(state) {
        if (state is ServerConnectUiState.Verified) {
            onServerVerified()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AuthScaffold(
            title = stringResource(Res.string.connect_connect_to_server),
            subtitle = stringResource(Res.string.connect_connect_to_server_subtitle),
            onBack = onBack,
        ) {
            val isVerifying = state is ServerConnectUiState.Verifying
            val errorState = state as? ServerConnectUiState.Error

            ListenUpTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    viewModel.clearError()
                },
                label = stringResource(Res.string.connect_server_url),
                placeholder = stringResource(Res.string.connect_server_url_placeholder),
                isError = errorState != null,
                supportingText = errorState?.error?.message,
                leadingIcon = Icons.Outlined.Link,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { viewModel.submitUrl(serverUrl) }),
            )

            AuthHelperCard(
                icon = Icons.Outlined.Lightbulb,
                text = stringResource(Res.string.connect_server_url_hint),
            )

            ListenUpButton(
                text = stringResource(Res.string.connect_connect),
                onClick = { viewModel.submitUrl(serverUrl) },
                leadingIcon = Icons.Outlined.CloudDone,
                isLoading = isVerifying,
                enabled = serverUrl.isNotBlank() && !isVerifying,
            )
        }
    }
}
