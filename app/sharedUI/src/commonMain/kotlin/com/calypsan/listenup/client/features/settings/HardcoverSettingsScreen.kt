package com.calypsan.listenup.client.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.error.localizedString
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsEvent
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.hardcover_disconnect
import listenup.composeapp.generated.resources.hardcover_disconnect_confirm_body
import listenup.composeapp.generated.resources.hardcover_disconnect_confirm_title
import listenup.composeapp.generated.resources.hardcover_loading
import listenup.composeapp.generated.resources.hardcover_not_offered
import listenup.composeapp.generated.resources.hardcover_screen_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Settings → Account → Hardcover: connect with Hardcover's device sign-in, watch it complete, see
 * who you're connected as, disconnect, and reconnect a broken connection.
 *
 * The server does the waiting while the user approves on Hardcover, so this screen is a pure render
 * of [HardcoverSettingsViewModel.uiState]; its one-shot events open the approval page in the browser
 * and surface failures in the screen's snackbar.
 *
 * @param onNavigateBack Navigate back to Settings.
 * @param modifier Modifier for the screen scaffold.
 * @param viewModel The Hardcover settings ViewModel, provided via Koin.
 */
@Composable
fun HardcoverSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HardcoverSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HardcoverSettingsEvent.OpenVerificationPage -> {
                    // No browser to hand the page to: the Linking screen already shows the address
                    // and the code, so the manual path is right there — nothing is stranded.
                    try {
                        uriHandler.openUri(event.url)
                    } catch (_: IllegalArgumentException) {
                    }
                }

                is HardcoverSettingsEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.error.localizedString())
                }
            }
        }
    }

    val isWide =
        currentWindowAdaptiveInfo()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    ListenUpScaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.hardcover_screen_title),
                onBack = onNavigateBack,
            )
        },
    ) { padding ->
        HardcoverSettingsContent(
            state = state,
            isWide = isWide,
            onConnect = viewModel::connect,
            onOpenHardcover = viewModel::openVerificationPage,
            onCancelLinking = viewModel::disconnect,
            onDisconnect = viewModel::disconnect,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The Hardcover screen's body for [state], without its scaffold. Disconnecting a connection asks
 * first — [onDisconnect] runs only once the user confirms — while cancelling a pending sign-in
 * ([onCancelLinking]) doesn't, since nothing is lost.
 *
 * @param isWide From the medium width class up, each phase uses the width: its lead region (the hero,
 *   or the code) sits beside the detail and the actions.
 */
@Composable
internal fun HardcoverSettingsContent(
    state: HardcoverSettingsUiState,
    isWide: Boolean,
    onConnect: () -> Unit,
    onOpenHardcover: () -> Unit,
    onCancelLinking: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDisconnect by rememberSaveable { mutableStateOf(false) }

    if (confirmingDisconnect) {
        ListenUpDestructiveDialog(
            onDismissRequest = { confirmingDisconnect = false },
            title = stringResource(Res.string.hardcover_disconnect_confirm_title),
            text = stringResource(Res.string.hardcover_disconnect_confirm_body),
            confirmText = stringResource(Res.string.hardcover_disconnect),
            onConfirm = {
                confirmingDisconnect = false
                onDisconnect()
            },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }

    when (state) {
        HardcoverSettingsUiState.Loading -> {
            FullScreenLoadingIndicator(
                modifier = modifier,
                message = stringResource(Res.string.hardcover_loading),
            )
        }

        HardcoverSettingsUiState.NotOffered -> {
            NotOfferedNote(modifier = modifier)
        }

        else -> {
            // One call site for every live phase, so the lead region's node survives a phase change
            // and its live region announces the new phase — "Connected, simon" the moment the user
            // approves on another device.
            val phase =
                hardcoverPhase(
                    state = state,
                    onConnect = onConnect,
                    onOpenHardcover = onOpenHardcover,
                    onCancelLinking = onCancelLinking,
                    onRequestDisconnect = { confirmingDisconnect = true },
                )
            PhaseLayout(phase = phase, isWide = isWide, modifier = modifier)
        }
    }
}

/**
 * A phase's three regions: [lead] carries what the phase is about (a hero, or the code), [detail]
 * supports it, and [actions] are the phase's buttons, primary first.
 */
internal class HardcoverPhase(
    val lead: @Composable ColumnScope.() -> Unit,
    val detail: (@Composable ColumnScope.() -> Unit)?,
    val actions: @Composable ColumnScope.() -> Unit,
)

/**
 * Narrow: one column, with the actions held at the bottom edge where a thumb reaches them. Wide: the
 * lead region beside the detail, with the actions following the detail in the second column.
 */
@Composable
private fun PhaseLayout(
    phase: HardcoverPhase,
    isWide: Boolean,
    modifier: Modifier = Modifier,
) {
    val leadRegion = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (isWide) {
        Row(
            modifier =
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenMargin)
                    .padding(top = Spacing.titleGap, bottom = Spacing.sectionGap),
            horizontalArrangement = Arrangement.spacedBy(Spacing.screenMargin),
        ) {
            Column(
                modifier = Modifier.weight(1f).then(leadRegion),
                verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
                content = phase.lead,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
            ) {
                phase.detail?.invoke(this)
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.titleGap),
                    content = phase.actions,
                )
            }
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.screenMargin)
                        .padding(top = Spacing.titleGap, bottom = Spacing.sectionGap),
                verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
            ) {
                Column(
                    modifier = leadRegion,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
                    content = phase.lead,
                )
                phase.detail?.invoke(this)
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screenMargin)
                        .padding(bottom = Spacing.sectionGap),
                verticalArrangement = Arrangement.spacedBy(Spacing.titleGap),
                content = phase.actions,
            )
        }
    }
}

@Composable
private fun NotOfferedNote(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = Spacing.screenMargin),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.hardcover_not_offered),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
