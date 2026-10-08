package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.client.domain.repository.PermissionsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A [PermissionsRepository] whose grants a test sets directly; [granted] is live. */
class FakePermissionsRepository(
    vararg initiallyGranted: Permission,
) : PermissionsRepository {
    /** The permissions the signed-in user holds right now. */
    val granted = MutableStateFlow(initiallyGranted.toSet())

    override fun observeCan(permission: Permission): Flow<Boolean> = granted.map { permission in it }
}
