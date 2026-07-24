package com.calypsan.listenup.client.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.TwoPaneMinWidth
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.HeroNavRow
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.TonalIconTile
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.settings.DeviceRow
import com.calypsan.listenup.client.presentation.settings.DevicesUiState
import com.calypsan.listenup.client.presentation.settings.DevicesViewModel
import com.calypsan.listenup.client.util.relativeLastActive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_retry
import listenup.composeapp.generated.resources.devices_active
import listenup.composeapp.generated.resources.devices_current_session
import listenup.composeapp.generated.resources.devices_empty
import listenup.composeapp.generated.resources.devices_note_sign_out_effect
import listenup.composeapp.generated.resources.devices_other_devices
import listenup.composeapp.generated.resources.devices_sign_out_all_others
import listenup.composeapp.generated.resources.devices_sign_out_device
import listenup.composeapp.generated.resources.devices_sign_out_everywhere
import listenup.composeapp.generated.resources.devices_sign_out_everywhere_confirm
import listenup.composeapp.generated.resources.devices_signed_in_count
import listenup.composeapp.generated.resources.devices_this_device
import listenup.composeapp.generated.resources.devices_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Lists the caller's active sessions ("devices") and lets them revoke a single
 * device or sign out everywhere.
 *
 * Rebuilt to the M3 Expressive mockup: a primaryContainer color-block hero showing the
 * current device, an Other Devices section with tinted icon tiles and per-row sign-out,
 * a note line, and a destructive "Sign out all other devices" button. Wide layout (≥
 * [TwoPaneMinWidth]) mirrors the desktop mockup with a fixed left session panel and a right
 * Other Devices column.
 *
 * The [DevicesViewModel] owns the authoritative session list; revoking a row re-fetches
 * rather than mutating optimistically, so the UI is a pure render of [DevicesUiState].
 *
 * @param onBack Navigate back to Settings.
 * @param onSignedOutEverywhere Invoked after a global sign-out completes (e.g. route to login).
 * @param viewModel The Devices ViewModel, provided via Koin.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    onSignedOutEverywhere: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DevicesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSignOutEverywhereDialog by remember { mutableStateOf(false) }

    if (showSignOutEverywhereDialog) {
        ListenUpDestructiveDialog(
            onDismissRequest = { showSignOutEverywhereDialog = false },
            title = stringResource(Res.string.devices_sign_out_everywhere),
            text = stringResource(Res.string.devices_sign_out_everywhere_confirm),
            confirmText = stringResource(Res.string.devices_sign_out_everywhere),
            onConfirm = {
                showSignOutEverywhereDialog = false
                viewModel.signOutEverywhere(onSignedOutEverywhere)
            },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isWide = windowSizeClass.isWidthAtLeastBreakpoint(TwoPaneMinWidth.value.toInt())

    DevicesBody(
        state = state,
        isWide = isWide,
        onBack = onBack,
        onRevokeDevice = viewModel::revokeDevice,
        onSignOutEverywhere = { showSignOutEverywhereDialog = true },
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

@OptIn(ExperimentalTime::class)
@Composable
private fun DevicesBody(
    state: DevicesUiState,
    isWide: Boolean,
    onBack: () -> Unit,
    onRevokeDevice: (String) -> Unit,
    onSignOutEverywhere: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is DevicesUiState.Loading -> {
            FullScreenLoadingIndicator(modifier = modifier)
        }

        is DevicesUiState.Error -> {
            Box(
                modifier =
                    modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = state.error.localized(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onRetry) {
                        Text(stringResource(Res.string.common_retry))
                    }
                }
            }
        }

        is DevicesUiState.Ready -> {
            val nowMs = remember { Clock.System.now().toEpochMilliseconds() }
            val currentDevice = state.devices.firstOrNull { it.isCurrent }
            val otherDevices = state.devices.filter { !it.isCurrent }

            if (isWide) {
                DevicesWideLayout(
                    state = state,
                    currentDevice = currentDevice,
                    otherDevices = otherDevices,
                    nowMs = nowMs,
                    onBack = onBack,
                    onRevokeDevice = onRevokeDevice,
                    onSignOutEverywhere = onSignOutEverywhere,
                    modifier = modifier,
                )
            } else {
                DevicesPhoneLayout(
                    state = state,
                    currentDevice = currentDevice,
                    otherDevices = otherDevices,
                    nowMs = nowMs,
                    onBack = onBack,
                    onRevokeDevice = onRevokeDevice,
                    onSignOutEverywhere = onSignOutEverywhere,
                    modifier = modifier,
                )
            }
        }
    }
}

// ─────────────────────────── Phone layout ────────────────────────────

@Composable
private fun DevicesPhoneLayout(
    state: DevicesUiState.Ready,
    currentDevice: DeviceRow?,
    otherDevices: List<DeviceRow>,
    nowMs: Long,
    onBack: () -> Unit,
    onRevokeDevice: (String) -> Unit,
    onSignOutEverywhere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // Hero — primaryContainer block with rounded bottom corners
        item {
            DevicesHero(
                totalCount = state.devices.size,
                currentDevice = currentDevice,
                onBack = onBack,
                isWide = false,
            )
        }

        // Other Devices section
        item {
            Spacer(modifier = Modifier.height(22.dp))
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                DevicesOtherSection(
                    otherDevices = otherDevices,
                    signingOut = state.signingOut,
                    nowMs = nowMs,
                    onRevokeDevice = onRevokeDevice,
                )
                Spacer(modifier = Modifier.height(16.dp))
                DevicesNoteRow()
                Spacer(modifier = Modifier.height(22.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SignOutAllOthersButton(onClick = onSignOutEverywhere)
                }
            }
        }
    }
}

// ─────────────────────────── Wide layout ─────────────────────────────

@Composable
private fun DevicesWideLayout(
    state: DevicesUiState.Ready,
    currentDevice: DeviceRow?,
    otherDevices: List<DeviceRow>,
    nowMs: Long,
    onBack: () -> Unit,
    onRevokeDevice: (String) -> Unit,
    onSignOutEverywhere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Wide hero — flat bar with back, title, count, and sign-out-all button
        DevicesWideHero(
            totalCount = state.devices.size,
            onBack = onBack,
            onSignOutEverywhere = onSignOutEverywhere,
        )

        // Two-column body: left = current session card, right = other devices list
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Left: current session panel (~400dp)
            Surface(
                modifier = Modifier.width(400.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Column(modifier = Modifier.padding(26.dp)) {
                    Text(
                        text = stringResource(Res.string.devices_current_session).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        letterSpacing = 1.2.sp,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    if (currentDevice != null) {
                        ThisDeviceCardContent(device = currentDevice, inHero = true)
                    }
                }
            }

            // Right: other devices + note
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item {
                    DevicesOtherSection(
                        otherDevices = otherDevices,
                        signingOut = state.signingOut,
                        nowMs = nowMs,
                        onRevokeDevice = onRevokeDevice,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    DevicesNoteRow()
                }
            }
        }
    }
}

// ─────────────────────────── Hero blocks ─────────────────────────────

@Composable
private fun DevicesHero(
    totalCount: Int,
    currentDevice: DeviceRow?,
    onBack: () -> Unit,
    isWide: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(bottomStart = 44.dp, bottomEnd = 44.dp),
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            // Nav row: back button + signed-in count pill
            HeroNavRow(
                onBack = onBack,
                buttonBackground = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f),
                applyStatusBarInset = !isWide,
                actions = {
                    SignedInCountPill(count = totalCount)
                },
            )

            // Title
            Text(
                text = stringResource(Res.string.devices_title),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 20.dp),
                letterSpacing = (-1.4).sp,
            )

            // This Device inner card
            if (currentDevice != null) {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.07f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(modifier = Modifier.padding(18.dp)) {
                        ThisDeviceCardContent(device = currentDevice, inHero = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun DevicesWideHero(
    totalCount: Int,
    onBack: () -> Unit,
    onSignOutEverywhere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Back button
            IconButton(
                onClick = onBack,
                modifier =
                    Modifier
                        .size(52.dp)
                        .background(
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.09f),
                            shape = MaterialTheme.shapes.medium,
                        ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Column {
                Text(
                    text = stringResource(Res.string.devices_title),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    letterSpacing = (-1.4).sp,
                )
                Text(
                    text = stringResource(Res.string.devices_signed_in_count, totalCount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Sign out all others — destructive tonal
            Button(
                onClick = onSignOutEverywhere,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.devices_sign_out_all_others),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ─────────────────────────── This Device card ─────────────────────────

@Composable
private fun ThisDeviceCardContent(
    device: DeviceRow,
    inHero: Boolean,
    modifier: Modifier = Modifier,
) {
    val visual = deviceVisualFor(device.deviceType)
    val inkColor =
        if (inHero) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val subColor = inkColor.copy(alpha = 0.75f)
    val tileBg =
        if (inHero) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
        } else {
            visual.tint.copy(alpha = 0.14f)
        }
    val tileIconTint = if (inHero) inkColor else visual.tint

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Device icon tile
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .background(tileBg, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = visual.icon,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = tileIconTint,
            )
        }

        // Text content
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = inkColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    letterSpacing = (-0.5).sp,
                )
                // "THIS DEVICE" chip
                ThisDeviceChip(inHero = inHero, inkColor = inkColor)
            }

            if (device.secondary.isNotBlank()) {
                Text(
                    text = device.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = subColor,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Active dot + label
            Row(
                modifier = Modifier.padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .background(Color(0xFF2FBF73), CircleShape),
                )
                Text(
                    text = stringResource(Res.string.devices_active),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = subColor,
                )
            }
        }
    }
}

@Composable
private fun ThisDeviceChip(
    inHero: Boolean,
    inkColor: Color,
) {
    Surface(
        shape = CircleShape,
        color =
            if (inHero) {
                inkColor.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.primary
            },
        contentColor = if (inHero) inkColor else MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            text = stringResource(Res.string.devices_this_device).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            letterSpacing = 0.3.sp,
        )
    }
}

// ─────────────────────────── Signed-in count pill ─────────────────────

@Composable
private fun SignedInCountPill(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Devices,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(Res.string.devices_signed_in_count, count),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ─────────────────────────── Other Devices section ───────────────────

@Composable
private fun DevicesOtherSection(
    otherDevices: List<DeviceRow>,
    signingOut: Set<String>,
    nowMs: Long,
    onRevokeDevice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionGroup(
        label = stringResource(Res.string.devices_other_devices),
        icon = Icons.Outlined.Devices,
        accent = MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
    ) {
        if (otherDevices.isEmpty()) {
            Text(
                text = stringResource(Res.string.devices_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            otherDevices.forEachIndexed { index, device ->
                if (index > 0) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 73.dp, end = 14.dp)
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                DeviceRowItem(
                    device = device,
                    isSigningOut = device.sessionId in signingOut,
                    nowMs = nowMs,
                    onRevoke = { onRevokeDevice(device.sessionId) },
                )
            }
        }
    }
}

@Composable
private fun DeviceRowItem(
    device: DeviceRow,
    isSigningOut: Boolean,
    nowMs: Long,
    onRevoke: () -> Unit,
) {
    val visual = deviceVisualFor(device.deviceType)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        // Tinted icon tile
        TonalIconTile(
            icon = visual.icon,
            size = 52.dp,
            accent = visual.tint,
        )

        // Device info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (device.secondary.isNotBlank()) {
                Text(
                    text = device.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = relativeLastActive(device.lastUsedAt, nowMs),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp),
            )
        }

        // Trailing: loading or sign-out button
        if (isSigningOut) {
            ListenUpLoadingIndicatorSmall()
        } else {
            IconButton(onClick = onRevoke) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Logout,
                    contentDescription = stringResource(Res.string.devices_sign_out_device),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ─────────────────────────── Note + Sign-out-all ─────────────────────

@Composable
private fun DevicesNoteRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.devices_note_sign_out_effect),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SignOutAllOthersButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Logout,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.devices_sign_out_all_others),
            fontWeight = FontWeight.Bold,
        )
    }
}
