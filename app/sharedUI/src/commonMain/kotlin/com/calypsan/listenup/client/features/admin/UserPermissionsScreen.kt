package com.calypsan.listenup.client.features.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.client.design.components.ButtonGroupChoice
import com.calypsan.listenup.client.design.components.ConnectedSelectButtonGroup
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.components.SectionColumns
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SettingToggleRow
import com.calypsan.listenup.client.design.components.TonalLabel
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.domain.model.PermissionPreset
import com.calypsan.listenup.client.presentation.admin.PermissionSection
import com.calypsan.listenup.client.presentation.admin.UserPermissionsUiState
import com.calypsan.listenup.client.presentation.admin.UserPermissionsViewModel
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.error.localizedString
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_admins_can_do_everything
import listenup.composeapp.generated.resources.admin_admins_can_do_everything_body
import listenup.composeapp.generated.resources.admin_admins_change_role_hint
import listenup.composeapp.generated.resources.admin_curate_library_warning
import listenup.composeapp.generated.resources.admin_make_admin_body
import listenup.composeapp.generated.resources.admin_make_admin_confirm
import listenup.composeapp.generated.resources.admin_make_admin_title
import listenup.composeapp.generated.resources.admin_older_server_one_permission
import listenup.composeapp.generated.resources.admin_preset
import listenup.composeapp.generated.resources.admin_role_owner
import listenup.composeapp.generated.resources.admin_unsaved_change_one
import listenup.composeapp.generated.resources.admin_unsaved_changes_many
import listenup.composeapp.generated.resources.book_edit_keep_editing
import listenup.composeapp.generated.resources.book_edit_unsaved_changes
import listenup.composeapp.generated.resources.book_edit_you_have_unsaved_changes_are
import listenup.composeapp.generated.resources.common_admin
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_discard
import listenup.composeapp.generated.resources.common_member
import listenup.composeapp.generated.resources.common_permissions
import listenup.composeapp.generated.resources.common_save
import org.jetbrains.compose.resources.stringResource

/** The screen's callbacks, threaded as one value instead of three lambdas. */
internal data class PermissionsActions(
    val onSelectPreset: (PermissionPreset) -> Unit,
    val onSetPermission: (Permission, Boolean) -> Unit,
    val onRequestRole: (UserRole) -> Unit,
)

/**
 * One user's role and permissions, edited as a draft. The save bar docks at the bottom only while
 * something is unsaved; Back with unsaved changes asks before throwing them away, and promoting to
 * Admin asks before it drafts the role.
 */
@Composable
fun UserPermissionsScreen(
    viewModel: UserPermissionsViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? UserPermissionsUiState.Ready
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }
    val actions =
        remember(viewModel) {
            PermissionsActions(viewModel::selectPreset, viewModel::setPermission, viewModel::requestRole)
        }

    PlatformBackHandler(enabled = ready?.hasChanges == true) { confirmingDiscard = true }
    LaunchedEffect(ready?.error) { ready?.error?.let { snackbarHostState.showSnackbar(it.localizedString()) } }

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.common_permissions),
                subtitle = ready?.run { user.displayableName },
                onBack = { if (ready?.hasChanges == true) confirmingDiscard = true else onBackClick() },
            )
        },
        bottomBar = {
            if (ready != null && ready.hasChanges) {
                PermissionsSaveBar(
                    changeCount = ready.changeCount,
                    isSaving = ready.isSaving,
                    onDiscard = viewModel::discard,
                    onSave = viewModel::save,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val current = state) {
            UserPermissionsUiState.Loading -> {
                FullScreenLoadingIndicator()
            }

            is UserPermissionsUiState.Error -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        text = current.error.localized(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is UserPermissionsUiState.Ready -> {
                UserPermissionsContent(state = current, actions = actions, modifier = Modifier.padding(padding))
            }
        }
    }

    if (ready?.isConfirmingAdminPromotion == true) {
        ListenUpAlertDialog(
            onDismissRequest = viewModel::cancelAdminPromotion,
            title = stringResource(Res.string.admin_make_admin_title, ready.user.displayableName),
            text = stringResource(Res.string.admin_make_admin_body),
            confirmText = stringResource(Res.string.admin_make_admin_confirm),
            onConfirm = viewModel::confirmAdminPromotion,
            dismissText = stringResource(Res.string.common_cancel),
            onDismiss = viewModel::cancelAdminPromotion,
            icon = Icons.Outlined.VerifiedUser,
        )
    }
    if (confirmingDiscard) {
        ListenUpDestructiveDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = stringResource(Res.string.book_edit_unsaved_changes),
            text = stringResource(Res.string.book_edit_you_have_unsaved_changes_are),
            confirmText = stringResource(Res.string.common_discard),
            onConfirm = {
                confirmingDiscard = false
                viewModel.discard()
                onBackClick()
            },
            dismissText = stringResource(Res.string.book_edit_keep_editing),
        )
    }
}

/**
 * The body for a loaded user: who they are and their role, then either "admins can do everything" or the
 * presets and the grouped toggles. Its sections flow into as many columns as the width holds — one on a
 * phone, side by side on a tablet.
 */
@Composable
internal fun UserPermissionsContent(
    state: UserPermissionsUiState.Ready,
    actions: PermissionsActions,
    modifier: Modifier = Modifier,
) {
    val locked = state.isProtected || state.isSaving
    val name = state.user.displayableName
    val warning = stringResource(Res.string.admin_curate_library_warning, name)
    SectionColumns(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenMargin, vertical = Spacing.sm),
    ) {
        section { IdentityCard(state = state, enabled = !locked, onRequestRole = actions.onRequestRole) }

        if (state.isAdminRole) {
            section { AdminsCanDoEverything(name = name) }
        } else {
            if (state.presetsShown) {
                section { PresetPicker(state = state, enabled = !locked, onSelect = actions.onSelectPreset) }
            }
            state.sections.forEach { permissionSection ->
                section {
                    PermissionGroup(
                        section = permissionSection,
                        enabled = !locked,
                        curateWarning = warning.takeIf { state.curateWarningShown },
                        onSetPermission = actions.onSetPermission,
                    )
                }
            }
            if (!state.presetsShown) {
                section { OlderServerNote() }
            }
        }
    }
}

@Composable
private fun IdentityCard(
    state: UserPermissionsUiState.Ready,
    enabled: Boolean,
    onRequestRole: (UserRole) -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier =
                Modifier.fillMaxWidth().padding(
                    start = Spacing.lg,
                    end = Spacing.sm,
                    top = Spacing.md,
                    bottom = Spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.user.displayableName, style = MaterialTheme.typography.titleMedium)
                Text(
                    state.user.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.user.isRoot) {
                TonalLabel(label = stringResource(Res.string.admin_role_owner))
            } else {
                RoleMenu(role = state.role, enabled = enabled, onRequestRole = onRequestRole)
            }
        }
    }
}

/** Member or Admin. Choosing Admin goes through the ViewModel's confirmation before it drafts anything. */
@Composable
private fun RoleMenu(
    role: UserRole,
    enabled: Boolean,
    onRequestRole: (UserRole) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListenUpButton(
            text = roleName(role),
            onClick = { open = true },
            enabled = enabled,
            filled = false,
            fillMaxWidth = false,
            trailingIcon = Icons.Outlined.ArrowDropDown,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(UserRole.MEMBER, UserRole.ADMIN).forEach { choice ->
                DropdownMenuItem(
                    text = { Text(roleName(choice)) },
                    onClick = {
                        open = false
                        onRequestRole(choice)
                    },
                )
            }
        }
    }
}

@Composable
private fun roleName(role: UserRole): String =
    stringResource(if (role == UserRole.MEMBER) Res.string.common_member else Res.string.common_admin)

@Composable
private fun PresetPicker(
    state: UserPermissionsUiState.Ready,
    enabled: Boolean,
    onSelect: (PermissionPreset) -> Unit,
) {
    val heading = stringResource(Res.string.admin_preset)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(
            modifier = Modifier.padding(start = Spacing.lg).semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = heading,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // Custom is arrived at, never picked: a label beside the heading, not a fourth button.
            if (state.preset == PermissionPreset.CUSTOM) {
                TonalLabel(
                    label = PermissionPreset.CUSTOM.title(),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    icon = Icons.Outlined.Tune,
                )
            }
        }
        ConnectedSelectButtonGroup(
            choices = PermissionPreset.pickable.map { ButtonGroupChoice(value = it, label = it.title()) },
            selected = state.preset,
            onSelect = { if (enabled) onSelect(it) },
            groupLabel = heading,
        )
        Text(
            text = state.preset.description(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
    }
}

/**
 * One group's toggles. While a Curate library grant is unsaved, the warning sits under that toggle,
 * inside the group, naming who gains the power and how far it reaches.
 */
@Composable
private fun PermissionGroup(
    section: PermissionSection,
    enabled: Boolean,
    curateWarning: String?,
    onSetPermission: (Permission, Boolean) -> Unit,
) {
    SectionGroup(label = section.group.title()) {
        section.rows.forEach { row ->
            SettingToggleRow(
                title = row.permission.title(),
                subtitle = row.permission.description(),
                checked = row.granted,
                enabled = enabled,
                onCheckedChange = { onSetPermission(row.permission, it) },
            )
            if (row.permission == Permission.CURATE_LIBRARY && curateWarning != null) {
                CurateWarning(text = curateWarning)
            }
        }
    }
}

@Composable
private fun CurateWarning(text: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun OlderServerNote() {
    Row(
        modifier = Modifier.padding(horizontal = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(Res.string.admin_older_server_one_permission),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AdminsCanDoEverything(name: String) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primary) {
                Icon(
                    imageVector = Icons.Outlined.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(Spacing.md).size(28.dp),
                )
            }
            Text(
                text = stringResource(Res.string.admin_admins_can_do_everything),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(Res.string.admin_admins_can_do_everything_body, name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(Res.string.admin_admins_change_role_hint, name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** Docked at the bottom while something is unsaved: the count, Discard and Save. */
@Composable
private fun PermissionsSaveBar(
    changeCount: Int,
    isSaving: Boolean,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = ZeroCorner, bottomEnd = ZeroCorner),
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text =
                    if (changeCount == 1) {
                        stringResource(Res.string.admin_unsaved_change_one)
                    } else {
                        stringResource(Res.string.admin_unsaved_changes_many, changeCount)
                    },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            ListenUpButton(
                text = stringResource(Res.string.common_discard),
                onClick = onDiscard,
                enabled = !isSaving,
                filled = false,
                fillMaxWidth = false,
            )
            ListenUpButton(
                text = stringResource(Res.string.common_save),
                onClick = onSave,
                isLoading = isSaving,
                fillMaxWidth = false,
            )
        }
    }
}

private val ZeroCorner = CornerSize(0.dp)
