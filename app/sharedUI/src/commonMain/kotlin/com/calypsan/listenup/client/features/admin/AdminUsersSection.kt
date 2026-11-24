package com.calypsan.listenup.client.features.admin

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.calypsan.listenup.api.dto.auth.PasswordResetRequest
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.PillChip
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.RoleChip
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.components.listenUpOutlinedBorder
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.util.relativeTime
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.AccessLabel
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_approve_reset
import listenup.composeapp.generated.resources.admin_deny_reset
import listenup.composeapp.generated.resources.admin_copy_link
import listenup.composeapp.generated.resources.admin_no_pending_registrations
import listenup.composeapp.generated.resources.admin_reset_code_recipient_fallback
import listenup.composeapp.generated.resources.admin_pending_invites
import listenup.composeapp.generated.resources.admin_pending_registrations
import listenup.composeapp.generated.resources.admin_reset_code_done
import listenup.composeapp.generated.resources.admin_reset_code_instruction
import listenup.composeapp.generated.resources.admin_reset_code_title
import listenup.composeapp.generated.resources.common_approve
import listenup.composeapp.generated.resources.common_copy
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_deny
import listenup.composeapp.generated.resources.common_invite
import listenup.composeapp.generated.resources.common_no_items_found
import listenup.composeapp.generated.resources.common_revoke
import listenup.composeapp.generated.resources.common_users

@Composable
internal fun UsersGroup(
    state: AdminUiState.Ready,
    onUserClick: (String) -> Unit,
    onDeleteUserClick: (AdminUserInfo) -> Unit,
    onInviteClick: () -> Unit,
) {
    SectionGroup(
        label = stringResource(Res.string.common_users),
        trailing = {
            ScallopBadge(
                size = 26.dp,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    text = state.users.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            PillChip(
                label = stringResource(Res.string.common_invite),
                onClick = onInviteClick,
                leadingIcon = ListenUpIcons.PersonAdd,
            )
        },
    ) {
        if (state.users.isEmpty()) {
            SectionSegment {
                Text(
                    text = stringResource(Res.string.common_no_items_found, "users"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
        } else {
            state.users.forEach { user ->
                UserRow(
                    user = user,
                    isDeleting = state.deletingUserId == user.id,
                    onClick = { onUserClick(user.id) },
                    onDeleteClick = { onDeleteUserClick(user) },
                )
            }
        }
    }
}

/** One person in the user list, named by their access label: Owner, Admin, or a member's preset. */
@Composable
internal fun UserRow(
    user: AdminUserInfo,
    isDeleting: Boolean,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val isElevated = user.access == AccessLabel.OWNER || user.access == AccessLabel.ADMIN
    val roleLabel = user.access.title()
    SettingRow(
        title = user.displayName ?: user.email,
        subtitle = user.email,
        onClick = onClick,
        leading = { UserAvatar(userId = user.id, size = AvatarSize.Medium) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RoleChip(label = roleLabel, isRoot = isElevated)
            if (!user.isProtected) {
                if (isDeleting) {
                    ListenUpLoadingIndicatorSmall()
                } else {
                    IconButton(
                        onClick = {
                            haptics.press()
                            onDeleteClick()
                        },
                    ) {
                        Icon(
                            imageVector = ListenUpIcons.Delete,
                            contentDescription = stringResource(Res.string.common_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PendingRegistrationsGroup(
    state: AdminUiState.Ready,
    onApproveUserClick: (AdminUserInfo) -> Unit,
    onDenyUserClick: (AdminUserInfo) -> Unit,
) {
    SectionGroup(
        label = stringResource(Res.string.admin_pending_registrations),
    ) {
        if (state.pendingUsers.isEmpty()) {
            SectionSegment {
                Text(
                    text = stringResource(Res.string.admin_no_pending_registrations),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
        } else {
            state.pendingUsers.forEach { user ->
                PendingUserRow(
                    user = user,
                    isApproving = state.approvingUserId == user.id,
                    isDenying = state.denyingUserId == user.id,
                    onApproveClick = { onApproveUserClick(user) },
                    onDenyClick = { onDenyUserClick(user) },
                )
            }
        }
    }
}

/**
 * Below this row width, the deny/approve pair collapses from labeled buttons to compact icon
 * buttons. Two labeled buttons overflow [SettingRow]'s weightless trailing slot on a phone and crush
 * the name/email column (which wraps, never truncates), so on narrow layouts the icons keep the row
 * uncramped; tablet/desktop widths keep the clearer labels.
 */
private val PENDING_ACTION_LABEL_MIN_WIDTH = 440.dp

@Composable
private fun PendingUserRow(
    user: AdminUserInfo,
    isApproving: Boolean,
    isDenying: Boolean,
    onApproveClick: () -> Unit,
    onDenyClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val name =
        user.displayName
            ?: "${user.firstName.orEmpty()} ${user.lastName.orEmpty()}".trim().ifEmpty { user.email }
    val denyLabel = stringResource(Res.string.common_deny)
    val approveLabel = stringResource(Res.string.common_approve)
    BoxWithConstraints {
        val compact = maxWidth < PENDING_ACTION_LABEL_MIN_WIDTH
        SettingRow(
            title = name,
            subtitle = user.email,
            // A pending registrant has no server-side public profile yet, so drive initials from
            // their name rather than the generic add-person glyph / an indefinite loading circle.
            leading = { UserAvatar(userId = user.id, size = AvatarSize.Medium, fallbackName = name) },
        ) {
            when {
                isApproving || isDenying -> {
                    ListenUpLoadingIndicatorSmall()
                }

                compact -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedIconButton(
                            onClick = {
                                haptics.press()
                                onDenyClick()
                            },
                        ) {
                            Icon(
                                imageVector = ListenUpIcons.Close,
                                contentDescription = denyLabel,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        FilledTonalIconButton(
                            onClick = {
                                haptics.press()
                                onApproveClick()
                            },
                        ) {
                            Icon(
                                imageVector = ListenUpIcons.Check,
                                contentDescription = approveLabel,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            border = listenUpOutlinedBorder(),
                            onClick = {
                                haptics.press()
                                onDenyClick()
                            },
                        ) {
                            Icon(
                                imageVector = ListenUpIcons.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(denyLabel)
                        }
                        FilledTonalButton(
                            onClick = {
                                haptics.press()
                                onApproveClick()
                            },
                        ) {
                            Icon(
                                imageVector = ListenUpIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(approveLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PendingInvitesGroup(
    state: AdminUiState.Ready,
    onCopyInviteClick: (InviteInfo) -> Unit,
    onRevokeInviteClick: (InviteInfo) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.secondary
    SectionGroup(
        label = stringResource(Res.string.admin_pending_invites),
    ) {
        state.pendingInvites.forEach { invite ->
            InviteRow(
                invite = invite,
                accent = accent,
                isRevoking = state.revokingInviteId == invite.id,
                onCopyClick = { onCopyInviteClick(invite) },
                onRevokeClick = { onRevokeInviteClick(invite) },
            )
        }
    }
}

@Composable
private fun InviteRow(
    invite: InviteInfo,
    accent: Color,
    isRevoking: Boolean,
    onCopyClick: () -> Unit,
    onRevokeClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    SettingRow(
        icon = ListenUpIcons.PersonAdd,
        accent = accent,
        title = invite.name,
        subtitle = invite.email,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RoleChip(label = invite.role.replaceFirstChar { it.uppercase() })
            IconButton(
                onClick = {
                    haptics.press()
                    onCopyClick()
                },
            ) {
                Icon(
                    imageVector = ListenUpIcons.ContentCopy,
                    contentDescription = stringResource(Res.string.admin_copy_link),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isRevoking) {
                ListenUpLoadingIndicatorSmall()
            } else {
                IconButton(
                    onClick = {
                        haptics.press()
                        onRevokeClick()
                    },
                ) {
                    Icon(
                        imageVector = ListenUpIcons.Delete,
                        contentDescription = stringResource(Res.string.common_revoke),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PasswordResetRow(
    request: PasswordResetRequest,
    isDeciding: Boolean,
    isLocked: Boolean,
    onApproveClick: () -> Unit,
    onDenyClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val approveLabel = stringResource(Res.string.admin_approve_reset)
    val denyLabel = stringResource(Res.string.admin_deny_reset)
    SettingRow(
        title = request.displayName,
        subtitle = "${request.email} · ${relativeTime(request.requestedAt)}",
        leading = {
            UserAvatar(
                userId = request.userId.value,
                size = AvatarSize.Medium,
                fallbackName = request.displayName,
            )
        },
    ) {
        if (isDeciding) {
            ListenUpLoadingIndicatorSmall()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedIconButton(
                    onClick = {
                        haptics.press()
                        onDenyClick()
                    },
                    enabled = !isLocked,
                ) {
                    Icon(
                        imageVector = ListenUpIcons.Close,
                        contentDescription = denyLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
                FilledTonalIconButton(
                    onClick = {
                        haptics.press()
                        onApproveClick()
                    },
                    enabled = !isLocked,
                ) {
                    Icon(
                        imageVector = ListenUpIcons.Check,
                        contentDescription = approveLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * The one-time reset code, shown exactly once. Dismissal is **only** via the explicit "Done"
 * button — [DialogProperties] disables both back-press and outside-tap dismissal, and
 * `onDismissRequest` is a no-op, so the code cannot vanish before the admin has read or
 * copied it. The instruction text is load-bearing, not decoration: conveying the code out of
 * band (phone, in person, a chat the admin and requester already use) IS the identity check
 * this whole design rests on, so this dialog offers no way to send it back through the app.
 */
@Composable
internal fun PasswordResetCodeDialog(
    code: String,
    recipientName: String?,
    onCopyClick: () -> Unit,
    onDone: () -> Unit,
) {
    val name = recipientName ?: stringResource(Res.string.admin_reset_code_recipient_fallback)
    ListenUpAlertDialog(
        onDismissRequest = {},
        title = stringResource(Res.string.admin_reset_code_title, name),
        confirmText = stringResource(Res.string.admin_reset_code_done),
        onConfirm = onDone,
        dismissText = stringResource(Res.string.common_copy),
        onDismiss = onCopyClick,
        icon = ListenUpIcons.Key,
        dismissIcon = ListenUpIcons.ContentCopy,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(Res.string.admin_reset_code_instruction, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
