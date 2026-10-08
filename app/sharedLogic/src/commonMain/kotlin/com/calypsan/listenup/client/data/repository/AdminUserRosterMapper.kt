package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.AdminUserRosterEntity
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.UserPermissions

/**
 * Maps a synced [AdminUserRosterEntity] row (from the Room-backed `admin_user_roster` sync
 * domain) to the admin-screen domain model [AdminUserInfo]. Mirrors `User.toAdminUserInfo`'s
 * field mapping — no first/last name (only [AdminUserRosterEntity.displayName]);
 * [AdminUserInfo.displayableName] already falls back to displayName.
 *
 * The roster carries only `canEdit`, so the Story World flags take the [UserPermissions] defaults here:
 * the user list never shows them, and the detail screen reads them through `AdminRepository.getUser`.
 */
internal fun AdminUserRosterEntity.toAdminUserInfo(): AdminUserInfo =
    AdminUserInfo(
        id = id,
        email = email,
        displayName = displayName,
        firstName = null,
        lastName = null,
        isRoot = role == "ROOT",
        role = role,
        status = status,
        permissions =
            UserPermissions(
                canEditMetadata = canEdit,
                canCurateLibrary = canCurateLibrary,
                canContributeStoryWorld = canContributeStoryWorld,
                canCurateStoryWorld = canCurateStoryWorld,
            ),
        createdAt = accountCreatedAt.toString(),
    )
