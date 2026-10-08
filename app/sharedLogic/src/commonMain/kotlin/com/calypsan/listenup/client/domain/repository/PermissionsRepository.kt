package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.auth.Permission
import kotlinx.coroutines.flow.Flow

/**
 * The one place a ViewModel asks "may the signed-in user do this?". ROOT and ADMIN may do everything; a
 * MEMBER may do what their flags grant. Reads Room, so the answer is there offline, and it follows the
 * user row as sync updates it. ViewModels expose the answer as a capability boolean and never read
 * `isAdmin` for an editing decision.
 */
interface PermissionsRepository {
    /** Emits whether the signed-in user may do [permission]; false while nobody is signed in. */
    fun observeCan(permission: Permission): Flow<Boolean>
}
