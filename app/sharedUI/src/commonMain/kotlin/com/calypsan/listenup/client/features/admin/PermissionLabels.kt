package com.calypsan.listenup.client.features.admin

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.auth.PermissionGroup
import com.calypsan.listenup.client.domain.model.AccessLabel
import com.calypsan.listenup.client.domain.model.PermissionPreset
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_permission_contribute_story_world
import listenup.composeapp.generated.resources.admin_permission_contribute_story_world_description
import listenup.composeapp.generated.resources.admin_permission_curate_library
import listenup.composeapp.generated.resources.admin_permission_curate_library_description
import listenup.composeapp.generated.resources.admin_permission_curate_story_world
import listenup.composeapp.generated.resources.admin_permission_curate_story_world_description
import listenup.composeapp.generated.resources.admin_permission_edit_metadata
import listenup.composeapp.generated.resources.admin_permission_edit_metadata_description
import listenup.composeapp.generated.resources.admin_permission_group_library
import listenup.composeapp.generated.resources.admin_permission_group_story_world
import listenup.composeapp.generated.resources.admin_preset_contributor
import listenup.composeapp.generated.resources.admin_preset_contributor_description
import listenup.composeapp.generated.resources.admin_preset_custom
import listenup.composeapp.generated.resources.admin_preset_custom_description
import listenup.composeapp.generated.resources.admin_preset_librarian
import listenup.composeapp.generated.resources.admin_preset_librarian_description
import listenup.composeapp.generated.resources.admin_preset_listener
import listenup.composeapp.generated.resources.admin_preset_listener_description
import listenup.composeapp.generated.resources.admin_role_owner
import listenup.composeapp.generated.resources.common_admin
import listenup.composeapp.generated.resources.common_member
import org.jetbrains.compose.resources.stringResource

/** The toggle's title. */
@Composable
internal fun Permission.title(): String =
    when (this) {
        Permission.EDIT_METADATA -> stringResource(Res.string.admin_permission_edit_metadata)
        Permission.CURATE_LIBRARY -> stringResource(Res.string.admin_permission_curate_library)
        Permission.CONTRIBUTE_STORY_WORLD -> stringResource(Res.string.admin_permission_contribute_story_world)
        Permission.CURATE_STORY_WORLD -> stringResource(Res.string.admin_permission_curate_story_world)
        Permission.UNKNOWN -> ""
    }

/** The toggle's one-line description. */
@Composable
internal fun Permission.description(): String =
    when (this) {
        Permission.EDIT_METADATA -> {
            stringResource(Res.string.admin_permission_edit_metadata_description)
        }

        Permission.CURATE_LIBRARY -> {
            stringResource(Res.string.admin_permission_curate_library_description)
        }

        Permission.CONTRIBUTE_STORY_WORLD -> {
            stringResource(
                Res.string.admin_permission_contribute_story_world_description,
            )
        }

        Permission.CURATE_STORY_WORLD -> {
            stringResource(Res.string.admin_permission_curate_story_world_description)
        }

        Permission.UNKNOWN -> {
            ""
        }
    }

/** The group heading. */
@Composable
internal fun PermissionGroup.title(): String =
    when (this) {
        PermissionGroup.LIBRARY -> stringResource(Res.string.admin_permission_group_library)
        PermissionGroup.STORY_WORLD -> stringResource(Res.string.admin_permission_group_story_world)
        PermissionGroup.UNKNOWN -> ""
    }

/** The preset's name. */
@Composable
internal fun PermissionPreset.title(): String =
    when (this) {
        PermissionPreset.LISTENER -> stringResource(Res.string.admin_preset_listener)
        PermissionPreset.CONTRIBUTOR -> stringResource(Res.string.admin_preset_contributor)
        PermissionPreset.LIBRARIAN -> stringResource(Res.string.admin_preset_librarian)
        PermissionPreset.CUSTOM -> stringResource(Res.string.admin_preset_custom)
    }

/** What the preset means, shown under the picker. */
@Composable
internal fun PermissionPreset.description(): String =
    when (this) {
        PermissionPreset.LISTENER -> stringResource(Res.string.admin_preset_listener_description)
        PermissionPreset.CONTRIBUTOR -> stringResource(Res.string.admin_preset_contributor_description)
        PermissionPreset.LIBRARIAN -> stringResource(Res.string.admin_preset_librarian_description)
        PermissionPreset.CUSTOM -> stringResource(Res.string.admin_preset_custom_description)
    }

/** How the user list names someone. */
@Composable
internal fun AccessLabel.title(): String =
    when (this) {
        AccessLabel.OWNER -> stringResource(Res.string.admin_role_owner)
        AccessLabel.ADMIN -> stringResource(Res.string.common_admin)
        AccessLabel.MEMBER -> stringResource(Res.string.common_member)
        AccessLabel.LISTENER -> stringResource(Res.string.admin_preset_listener)
        AccessLabel.CONTRIBUTOR -> stringResource(Res.string.admin_preset_contributor)
        AccessLabel.LIBRARIAN -> stringResource(Res.string.admin_preset_librarian)
        AccessLabel.CUSTOM -> stringResource(Res.string.admin_preset_custom)
    }
