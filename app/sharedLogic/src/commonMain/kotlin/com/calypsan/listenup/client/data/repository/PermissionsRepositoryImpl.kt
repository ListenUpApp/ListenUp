package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.domain.model.UserPermissions
import com.calypsan.listenup.client.domain.repository.PermissionsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [PermissionsRepository] over the signed-in user's Room row. `UserEntity.isRoot` is the admin bit
 * (ROOT or ADMIN), and admins pass every permission.
 */
internal class PermissionsRepositoryImpl(
    private val userDao: UserDao,
) : PermissionsRepository {
    override fun observeCan(permission: Permission): Flow<Boolean> =
        userDao
            .observeCurrentUser()
            .map { user ->
                user != null &&
                    (
                        user.isRoot ||
                            UserPermissions(canEditMetadata = user.canEdit, canCurateLibrary = user.canCurateLibrary)
                                .allows(permission)
                    )
            }.distinctUntilChanged()
}
