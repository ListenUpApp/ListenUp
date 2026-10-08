package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.db.UserRoleColumn

/**
 * True for ROOT and ADMIN — the roles that hold every permission. The one place the server spells the
 * admin check; `OneAdminCheckRule` fails the build if a private copy grows back.
 */
internal fun UserRole.isAdmin(): Boolean = this == UserRole.ROOT || this == UserRole.ADMIN

/** [isAdmin] for a stored role column. */
internal fun UserRoleColumn.isAdmin(): Boolean = this == UserRoleColumn.ROOT || this == UserRoleColumn.ADMIN
