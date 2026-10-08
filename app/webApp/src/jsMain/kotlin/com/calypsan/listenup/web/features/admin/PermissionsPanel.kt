package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.auth.PermissionGroup
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.client.domain.model.AccessLabel
import com.calypsan.listenup.client.domain.model.PermissionPreset
import com.calypsan.listenup.client.presentation.admin.PermissionRow
import com.calypsan.listenup.client.presentation.admin.UserPermissionsUiState
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.SelectField
import com.calypsan.listenup.web.design.SelectOption
import com.calypsan.listenup.web.design.SwitchField
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** The permissions panel's callbacks; each defaults to nothing so specs pass only what they press. */
class PermissionsPanelActions(
    val onSelectPreset: (PermissionPreset) -> Unit = {},
    val onSetPermission: (Permission, Boolean) -> Unit = { _, _ -> },
    val onRequestRole: (UserRole) -> Unit = {},
    val onConfirmAdminPromotion: () -> Unit = {},
    val onCancelAdminPromotion: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onSave: () -> Unit = {},
)

/**
 * One member's role and permissions, inside their page — the web has the room, so this is not a
 * sub-page. Presets are a radio group drawn as cards; each switch is named by its row; a sticky bar
 * names what changed and offers Discard.
 *
 * ⛔ Everything here edits a draft. Nothing reaches the server until Save, and Save sends only what
 * changed — so a switch moving instantly is the draft, not a write.
 */
@Composable
fun PermissionsPanel(
    state: UserPermissionsUiState,
    actions: PermissionsPanelActions,
) {
    val ready = state as? UserPermissionsUiState.Ready ?: return
    val locked = ready.isProtected || ready.isSaving
    val name = ready.user.displayableName

    Div(attrs = { classes("perm") }) {
        Panel(
            title = "Permissions",
            trailing = { if (!ready.user.isRoot) RolePicker(ready, actions.onRequestRole) },
        ) {
            if (ready.user.isRoot) {
                P(attrs = { classes("usr-note") }) {
                    Text("This is the server's owner. Their permissions can't be changed from here.")
                }
            }

            if (ready.isAdminRole) {
                AdminsCanDoEverything(name)
            } else {
                if (ready.presetsShown) PresetCards(ready, enabled = !locked, onSelect = actions.onSelectPreset)
                ready.sections.forEach { section ->
                    Div(attrs = { classes("perm-group") }) {
                        H3(attrs = { classes("perm-group-h") }) { Text(groupTitle(section.group)) }
                        section.rows.forEach { row ->
                            PermissionRowView(
                                row = row,
                                enabled = !locked,
                                warning =
                                    if (row.permission == Permission.CURATE_LIBRARY && ready.curateWarningShown) {
                                        "$name will be able to merge and delete these for everyone on this server. " +
                                            "Deletes can't be undone."
                                    } else {
                                        null
                                    },
                                onSet = actions.onSetPermission,
                            )
                        }
                    }
                }
                if (!ready.presetsShown) {
                    P(attrs = { classes("usr-note", "perm-older") }) {
                        Text("This server can only set this one permission. Update ListenUp on the server for the rest.")
                    }
                }
            }

            ready.error?.let { failure ->
                P(attrs = {
                    classes("usr-err")
                    attr("role", "alert")
                }) { Text(failure.message) }
            }
        }

        if (ready.hasChanges) SaveBar(ready, name, actions)
    }

    ConfirmDialog(
        open = ready.isConfirmingAdminPromotion,
        title = "Make $name an admin?",
        body =
            "Admins can do everything: edit, merge and delete anything in the library, and manage people, " +
                "collections and server settings.",
        confirmLabel = "Make admin",
        onConfirm = { actions.onConfirmAdminPromotion() },
        onDismiss = { actions.onCancelAdminPromotion() },
    )
}

/**
 * Member or Admin. Keyed on the drafted role and the confirmation, because a native select moves the
 * moment it is picked: choosing Admin only *asks*, and a cancelled ask has to put the select back.
 */
@Composable
private fun RolePicker(
    ready: UserPermissionsUiState.Ready,
    onRequestRole: (UserRole) -> Unit,
) {
    Div(attrs = { classes("perm-role") }) {
        key(ready.role, ready.isConfirmingAdminPromotion) {
            SelectField(
                label = "Role",
                value = if (ready.role == UserRole.MEMBER) UserRole.MEMBER.name else UserRole.ADMIN.name,
                options =
                    listOf(
                        SelectOption(UserRole.MEMBER.name, "Member"),
                        SelectOption(UserRole.ADMIN.name, "Admin"),
                    ),
                onSelect = { value ->
                    UserRole.entries.firstOrNull { it.name == value }?.let(onRequestRole)
                },
            )
        }
    }
}

@Composable
private fun AdminsCanDoEverything(name: String) {
    Div(attrs = { classes("perm-admin") }) {
        H3(attrs = { classes("perm-admin-h") }) { Text("Admins can do everything") }
        P {
            Text(
                "$name can edit, merge and delete anything in the library, and manage people, collections and " +
                    "server settings.",
            )
        }
        P { Text("To choose what $name can do, change their role to Member.") }
    }
}

@Composable
private fun PresetCards(
    ready: UserPermissionsUiState.Ready,
    enabled: Boolean,
    onSelect: (PermissionPreset) -> Unit,
) {
    Div(attrs = {
        classes("perm-presets")
        attr("role", "radiogroup")
        attr("aria-labelledby", PRESET_HEADING_ID)
    }) {
        Div(attrs = { classes("perm-group-h") }) {
            Span(attrs = { attr("id", PRESET_HEADING_ID) }) { Text("Preset") }
            if (ready.preset == PermissionPreset.CUSTOM) Span(attrs = { classes("perm-custom") }) { Text("Custom") }
        }
        Div(attrs = { classes("perm-cards") }) {
            PermissionPreset.pickable.forEach { preset ->
                val chosen = preset == ready.preset
                Label(attrs = {
                    classes("perm-card")
                    if (chosen) classes("on")
                    if (!enabled) classes("off")
                }) {
                    Input(type = InputType.Radio, attrs = {
                        attr("name", "permission-preset")
                        if (chosen) attr("checked", "")
                        if (!enabled) attr("disabled", "")
                        // The property, not just the attribute: once clicked, the attribute stops
                        // governing the dot, and the draft is what decides which card is chosen.
                        prop({ input: org.w3c.dom.HTMLInputElement, on: Boolean -> input.checked = on }, chosen)
                        onClick { if (enabled) onSelect(preset) }
                    })
                    Span(attrs = { classes("perm-card-text") }) {
                        Span(attrs = { classes("perm-card-t") }) { Text(presetTitle(preset)) }
                        Span(attrs = { classes("perm-card-s") }) { Text(presetDescription(preset)) }
                    }
                }
            }
        }
        if (ready.preset == PermissionPreset.CUSTOM) {
            P(attrs = { classes("perm-custom-note") }) { Text(presetDescription(PermissionPreset.CUSTOM)) }
        }
    }
}

/** One switch: named by its title, described by the sentence under it, warned under that when needed. */
@Composable
private fun PermissionRowView(
    row: PermissionRow,
    enabled: Boolean,
    warning: String?,
    onSet: (Permission, Boolean) -> Unit,
) {
    val describedBy = "perm-${row.permission.name.lowercase()}-d"
    val warnedBy = "perm-${row.permission.name.lowercase()}-w"
    Div(attrs = { classes("perm-row") }) {
        SwitchField(
            label = permissionTitle(row.permission),
            checked = row.granted,
            onChange = { onSet(row.permission, it) },
            enabled = enabled,
            describedBy = if (warning != null) "$describedBy $warnedBy" else describedBy,
        )
        Span(attrs = {
            classes("perm-row-d")
            attr("id", describedBy)
        }) {
            if (row.isUnsaved) Span(attrs = { classes("perm-unsaved") }) { Text("Unsaved") }
            Text(permissionDescription(row.permission))
        }
        warning?.let {
            P(attrs = {
                classes("perm-warn")
                attr("id", warnedBy)
                attr("role", "status")
            }) { Text(it) }
        }
    }
}

@Composable
private fun SaveBar(
    ready: UserPermissionsUiState.Ready,
    name: String,
    actions: PermissionsPanelActions,
) {
    Div(attrs = {
        classes("perm-bar")
        attr("role", "region")
        attr("aria-label", "Unsaved changes")
    }) {
        Span(attrs = { classes("perm-bar-t") }) { Text("${changeWords(ready.changeCount)} to $name's permissions") }
        Button(
            kind = ButtonKind.Secondary,
            onClick = { actions.onDiscard() },
            enabled = !ready.isSaving,
            attrs = { classes("perm-bar-discard") },
        ) { Text("Discard") }
        Button(kind = ButtonKind.Primary, onClick = { actions.onSave() }, pressable = !ready.isSaving) {
            Text(if (ready.isSaving) "Saving…" else "Save changes")
        }
    }
}

private fun changeWords(count: Int): String = if (count == 1) "1 unsaved change" else "$count unsaved changes"

private fun permissionTitle(permission: Permission): String =
    when (permission) {
        Permission.EDIT_METADATA -> "Edit metadata"
        Permission.CURATE_LIBRARY -> "Curate library"
        Permission.UNKNOWN -> ""
    }

private fun permissionDescription(permission: Permission): String =
    when (permission) {
        Permission.EDIT_METADATA -> "Fix titles, covers, chapters and matches."
        Permission.CURATE_LIBRARY -> "Merge or delete series, authors, genres, tags and moods."
        Permission.UNKNOWN -> ""
    }

private fun groupTitle(group: PermissionGroup): String =
    when (group) {
        PermissionGroup.LIBRARY -> "Library"
        PermissionGroup.UNKNOWN -> ""
    }

private fun presetTitle(preset: PermissionPreset): String =
    when (preset) {
        PermissionPreset.LISTENER -> "Listener"
        PermissionPreset.CONTRIBUTOR -> "Contributor"
        PermissionPreset.LIBRARIAN -> "Librarian"
        PermissionPreset.CUSTOM -> "Custom"
    }

private fun presetDescription(preset: PermissionPreset): String =
    when (preset) {
        PermissionPreset.LISTENER -> "Listens and browses. Changes nothing."
        PermissionPreset.CONTRIBUTOR -> "Fixes books and their details. Can't merge or delete."
        PermissionPreset.LIBRARIAN -> "Everything a member can do, including merging and deleting."
        PermissionPreset.CUSTOM -> "These don't match a preset. Pick one to start again from it."
    }

/** How the people list names someone: their role, or a member's preset. */
internal fun accessTitle(access: AccessLabel): String =
    when (access) {
        AccessLabel.OWNER -> "Owner"
        AccessLabel.ADMIN -> "Admin"
        AccessLabel.MEMBER -> "Member"
        AccessLabel.LISTENER -> "Listener"
        AccessLabel.CONTRIBUTOR -> "Contributor"
        AccessLabel.LIBRARIAN -> "Librarian"
        AccessLabel.CUSTOM -> "Custom"
    }

private const val PRESET_HEADING_ID = "perm-preset-h"
