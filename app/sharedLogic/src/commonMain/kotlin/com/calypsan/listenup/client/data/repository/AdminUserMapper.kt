package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.UserPermissions

/**
 * Maps the contract [User] returned by [com.calypsan.listenup.api.AdminUserService] to the
 * admin-screen domain model [AdminUserInfo]. The contract has no first/last name (only
 * [User.displayName]); [AdminUserInfo.displayableName] already falls back to displayName.
 */
internal fun User.toAdminUserInfo(): AdminUserInfo =
    AdminUserInfo(
        id = id.value,
        email = email,
        displayName = displayName,
        firstName = null,
        lastName = null,
        isRoot = role == UserRole.ROOT,
        role = role.name,
        status = status.name,
        permissions = UserPermissions(canShare = permissions.canShare),
        createdAt = createdAt.toString(),
    )
