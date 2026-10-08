package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [UserRepository] whose only live part is the admin flag — flip [admin] to switch a
 * test between an admin's device and a member's. Profile reads answer "no user".
 */
class FakeUserRepository(
    initialIsAdmin: Boolean = false,
) : UserRepository {
    /** The signed-in user's admin flag, as [observeIsAdmin] reports it. */
    val admin = MutableStateFlow(initialIsAdmin)

    override fun observeCurrentUser(): Flow<User?> = flowOf(null)

    override fun observeIsAdmin(): Flow<Boolean> = admin

    override suspend fun getCurrentUser(): User? = null

    override suspend fun saveUser(user: User) = Unit

    override suspend fun clearUsers() = Unit

    override suspend fun refreshCurrentUser(): User? = null
}
