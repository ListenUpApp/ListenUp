package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.OpenCampfireSummary
import com.calypsan.listenup.client.design.components.ExpressiveCheckbox
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.PillChip
import com.calypsan.listenup.client.presentation.campfire.CampfireCreateUiState
import com.calypsan.listenup.client.presentation.campfire.CampfireInviteUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_control_everyone
import listenup.composeapp.generated.resources.campfire_control_host_only
import listenup.composeapp.generated.resources.campfire_create_start
import listenup.composeapp.generated.resources.campfire_invite_only
import listenup.composeapp.generated.resources.campfire_invite_only_no_users
import listenup.composeapp.generated.resources.campfire_join
import listenup.composeapp.generated.resources.campfire_listening_now
import listenup.composeapp.generated.resources.campfire_listening_now_count
import listenup.composeapp.generated.resources.campfire_sheet_host_title
import listenup.composeapp.generated.resources.campfire_sheet_title
import org.jetbrains.compose.resources.stringResource

/**
 * Book Detail's Campfire sheet (campfire implementation plan, Task 10) — lists currently open
 * sessions for this book (tap to join) and hosts the create/invite flow (control mode, invite-only,
 * invited-user picker). Opened via [CampfireEntryPoint].
 *
 * @param liveCampfires Open sessions for this book (see `BookDetailViewModel.liveCampfires`).
 * @param createState Create-sheet RPC state.
 * @param inviteState Invite-sheet user-picker state, populated by [onLoadInvitableUsers].
 * @param onJoin Called with the tapped session's id.
 * @param onCreate Called with the chosen [CampfireSettings] when "Start Campfire" is tapped.
 * @param onLoadInvitableUsers Called once when invite-only is switched on, to populate [inviteState].
 * @param onDismiss Dismiss the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampfireBookSheet(
    liveCampfires: List<OpenCampfireSummary>,
    createState: CampfireCreateUiState,
    inviteState: CampfireInviteUiState,
    onJoin: (CampfireId) -> Unit,
    onCreate: (CampfireSettings) -> Unit,
    onLoadInvitableUsers: () -> Unit,
    onDismiss: () -> Unit,
) {
    var controlMode by remember { mutableStateOf(CampfireControlMode.EVERYONE) }
    var inviteOnly by remember { mutableStateOf(false) }
    var selectedUserIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(inviteOnly) {
        if (inviteOnly && inviteState is CampfireInviteUiState.Idle) onLoadInvitableUsers()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 12.dp).width(36.dp).height(5.dp),
                shape = RoundedCornerShape(3.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            ) {}
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(text = stringResource(Res.string.campfire_sheet_title), style = MaterialTheme.typography.titleLarge)

            if (liveCampfires.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(Res.string.campfire_listening_now),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                liveCampfires.forEach { summary ->
                    LiveCampfireRow(summary = summary, onJoin = { onJoin(summary.id) })
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(Res.string.campfire_sheet_host_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip(
                    label = stringResource(Res.string.campfire_control_everyone),
                    selected = controlMode == CampfireControlMode.EVERYONE,
                    onClick = { controlMode = CampfireControlMode.EVERYONE },
                )
                PillChip(
                    label = stringResource(Res.string.campfire_control_host_only),
                    selected = controlMode == CampfireControlMode.HOST_ONLY,
                    onClick = { controlMode = CampfireControlMode.HOST_ONLY },
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = stringResource(Res.string.campfire_invite_only), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = inviteOnly, onCheckedChange = { inviteOnly = it })
            }

            if (inviteOnly) {
                Spacer(Modifier.height(8.dp))
                InvitePicker(
                    inviteState = inviteState,
                    selectedUserIds = selectedUserIds,
                    onToggleUser = { userId ->
                        selectedUserIds =
                            if (userId in selectedUserIds) selectedUserIds - userId else selectedUserIds + userId
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            ListenUpButton(
                text = stringResource(Res.string.campfire_create_start),
                onClick = {
                    onCreate(
                        CampfireSettings(
                            controlMode = controlMode,
                            inviteOnly = inviteOnly,
                            invitedUserIds = selectedUserIds.toList(),
                        ),
                    )
                },
                isLoading = createState is CampfireCreateUiState.Creating,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LiveCampfireRow(
    summary: OpenCampfireSummary,
    onJoin: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.campfire_listening_now_count, summary.memberCount),
            style = MaterialTheme.typography.bodyMedium,
        )
        PillChip(label = stringResource(Res.string.campfire_join), onClick = onJoin, selected = true)
    }
}

@Composable
private fun InvitePicker(
    inviteState: CampfireInviteUiState,
    selectedUserIds: Set<String>,
    onToggleUser: (String) -> Unit,
) {
    when (inviteState) {
        CampfireInviteUiState.Idle, CampfireInviteUiState.Loading -> {
            ListenUpLoadingIndicator()
        }

        is CampfireInviteUiState.Ready -> {
            if (inviteState.users.isEmpty()) {
                Text(
                    text = stringResource(Res.string.campfire_invite_only_no_users),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    inviteState.users.forEach { user ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ExpressiveCheckbox(
                                checked = user.userId in selectedUserIds,
                                onCheckedChange = { onToggleUser(user.userId) },
                            )
                            Text(text = user.displayName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        is CampfireInviteUiState.Error -> {
            Text(
                text = inviteState.error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
