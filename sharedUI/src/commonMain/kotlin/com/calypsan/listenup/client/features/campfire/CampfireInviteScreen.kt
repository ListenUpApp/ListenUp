package com.calypsan.listenup.client.features.campfire

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.api.dto.campfire.CampfireInvitableUser
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.presentation.campfire.CampfireInviteUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.campfire_flow_invite_continue
import listenup.composeapp.generated.resources.campfire_flow_invite_continue_n
import listenup.composeapp.generated.resources.campfire_flow_invite_eyebrow
import listenup.composeapp.generated.resources.campfire_flow_invite_search_placeholder
import listenup.composeapp.generated.resources.campfire_flow_invite_title
import listenup.composeapp.generated.resources.campfire_invite_only_no_users
import org.jetbrains.compose.resources.stringResource

/**
 * Screen 2 of the full-screen Campfire flow — the invite picker (co-listening design spec's
 * 2026-07-11 lobby amendment, task L3). Reused for two call sites:
 *  - **Pre-session** (Create → Invite, `BookDetailScreen`'s campfire flow): [onContinue] hands the
 *    selected ids back to the caller, which creates the session with the full
 *    [com.calypsan.listenup.api.dto.campfire.CampfireSettings] in one shot — `createSession` is
 *    deferred to here (not called at the end of Create) because
 *    [com.calypsan.listenup.api.dto.campfire.CampfireSettings] already carries `invitedUserIds`, so
 *    there is no reason to create a lobby-phase room and immediately mutate it again for invites
 *    gathered one screen later.
 *  - **Live-room reinvite** (the Room screen's invite button): [onContinue] instead calls
 *    `CampfireViewModel.updateSettings` with the merged invite list and dismisses back to the room.
 *
 * @param excludedUserIds Users to hide from the picker entirely — already-joined members on the
 * reinvite call site. No presence ("listening now") caption: the invitable-user list carries no
 * presence data and building new presence plumbing for this caption is out of scope.
 */
@Composable
internal fun CampfireInviteScreen(
    inviteState: CampfireInviteUiState,
    excludedUserIds: Set<String>,
    onLoadInvitableUsers: () -> Unit,
    onBack: () -> Unit,
    onContinue: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    LaunchedEffect(Unit) {
        if (inviteState is CampfireInviteUiState.Idle) onLoadInvitableUsers()
    }

    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }

    CampfireBackdrop(
        stage = CampfireBackdropStage.EMBER,
        modifier = modifier.fillMaxSize(),
        reducedMotion = reducedMotion,
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CampfireFlowHeader(
                    eyebrow = stringResource(Res.string.campfire_flow_invite_eyebrow),
                    title = stringResource(Res.string.campfire_flow_invite_title),
                    onBack = onBack,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(
                                max = CampfireFlowContentMaxWidth,
                            ).fillMaxSize()
                            .padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(16.dp))
                    CampfireInviteSearchField(query = query, onQueryChange = { query = it })
                    Spacer(Modifier.height(12.dp))
                    CampfireInviteBody(
                        inviteState = inviteState,
                        query = query,
                        excludedUserIds = excludedUserIds,
                        selected = selected,
                        onToggleSelected = { userId ->
                            selected = if (userId in selected) selected - userId else selected + userId
                        },
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val continueLabel =
                    if (selected.isEmpty()) {
                        stringResource(Res.string.campfire_flow_invite_continue)
                    } else {
                        stringResource(Res.string.campfire_flow_invite_continue_n, selected.size)
                    }
                CampfireFireButton(
                    text = continueLabel,
                    onClick = { onContinue(selected.toList()) },
                    modifier =
                        Modifier
                            .widthIn(max = CampfireFlowContentMaxWidth)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun CampfireInviteSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(Res.string.campfire_flow_invite_search_placeholder)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CampfireFlowColors.OnGlassMuted) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = CircleShape,
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedTextColor = CampfireFlowColors.OnGlass,
                unfocusedTextColor = CampfireFlowColors.OnGlass,
                focusedContainerColor = CampfireFlowColors.Glass.copy(alpha = 0.62f),
                unfocusedContainerColor = CampfireFlowColors.Glass.copy(alpha = 0.62f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = CampfireFlowColors.GlassBorder,
                unfocusedPlaceholderColor = CampfireFlowColors.OnGlassFaint,
                focusedPlaceholderColor = CampfireFlowColors.OnGlassFaint,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
    )
}

/** Dispatches [inviteState] to a loading spinner, the filtered candidate list, or an error message. */
@Composable
private fun CampfireInviteBody(
    inviteState: CampfireInviteUiState,
    query: String,
    excludedUserIds: Set<String>,
    selected: Set<String>,
    onToggleSelected: (String) -> Unit,
) {
    when (inviteState) {
        CampfireInviteUiState.Idle, CampfireInviteUiState.Loading -> {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.TopCenter) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        is CampfireInviteUiState.Ready -> {
            val candidates =
                inviteState.users
                    .filterNot { it.userId in excludedUserIds }
                    .filter { it.displayName.contains(query, ignoreCase = true) }
            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(Res.string.campfire_invite_only_no_users),
                    color = CampfireFlowColors.OnGlassMuted,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(candidates, key = { it.userId }) { user ->
                        CampfireInviteUserRow(
                            user = user,
                            isSelected = user.userId in selected,
                            onToggle = { onToggleSelected(user.userId) },
                        )
                    }
                }
            }
        }

        is CampfireInviteUiState.Error -> {
            Text(
                text = inviteState.error.message,
                color = CampfireFlowColors.OnGlassMuted,
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}

@Composable
private fun CampfireInviteUserRow(
    user: CampfireInvitableUser,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UserAvatar(userId = user.userId, size = AvatarSize.Small, fallbackName = user.displayName)
        Text(
            text = user.displayName,
            color = CampfireFlowColors.OnGlass,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier =
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
