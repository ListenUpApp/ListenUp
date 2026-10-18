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

    /** What [refreshCurrentUser] fetches from "the server"; null answers as a failed fetch. Stored when non-null. */
    var serverUser: User? = null

    /** How many times [refreshCurrentUser] asked the server. */
    var refreshCalls = 0
        private set

    override suspend fun refreshCurrentUser(): User? {
        refreshCalls++
        serverUser?.let { currentUser.value = it }
        return serverUser
    }
}
