package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [UserRepository] whose live parts are the admin flag — flip [admin] to switch a test
 * between an admin's device and a member's — and [currentUser], "no user" until a test sets one.
 */
class FakeUserRepository(
    initialIsAdmin: Boolean = false,
) : UserRepository {
    /** The signed-in user's admin flag, as [observeIsAdmin] reports it. */
    val admin = MutableStateFlow(initialIsAdmin)

    /** The signed-in user, as [observeCurrentUser] reports it. */
    val currentUser = MutableStateFlow<User?>(null)

    override fun observeCurrentUser(): Flow<User?> = currentUser

    override fun observeIsAdmin(): Flow<Boolean> = admin

    override suspend fun getCurrentUser(): User? = currentUser.value

    override suspend fun saveUser(user: User) = Unit

    override suspend fun clearUsers() = Unit

    override suspend fun refreshCurrentUser(): User? = null
}
