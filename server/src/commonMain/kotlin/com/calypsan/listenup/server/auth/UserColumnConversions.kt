package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn

internal fun UserRoleColumn.toContract(): UserRole =
    when (this) {
        UserRoleColumn.ROOT -> UserRole.ROOT
        UserRoleColumn.ADMIN -> UserRole.ADMIN
        UserRoleColumn.MEMBER -> UserRole.MEMBER
    }

internal fun UserRole.toColumn(): UserRoleColumn =
    when (this) {
        UserRole.ROOT -> UserRoleColumn.ROOT
        UserRole.ADMIN -> UserRoleColumn.ADMIN
        UserRole.MEMBER -> UserRoleColumn.MEMBER
    }

internal fun UserStatusColumn.toContract(): UserStatus =
    when (this) {
        UserStatusColumn.ACTIVE -> UserStatus.ACTIVE
        UserStatusColumn.PENDING_APPROVAL -> UserStatus.PENDING_APPROVAL
        UserStatusColumn.DENIED -> UserStatus.DENIED
    }
