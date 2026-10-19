package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A browser whose database is rebuilt on a visit is signed in with no user row: Home had no name, no shelves and no
 * admin entry, and kept them missing after the server came back. The reader's own profile is fetched whenever it is
 * missing while signed in and the server can be asked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreCurrentUserTest :
    FunSpec({
        val rowan =
            User(
                id = UserId("u1"),
                email = "rowan@example.com",
                displayName = "Rowan Hale",
                isAdmin = true,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )

        test("signed in with no stored user, the user is fetched and stored") {
            runTest {
                val users = ServerUsers().apply { serverUser = rowan }
                backgroundScope.launch {
                    RestoreCurrentUser(users).keepRestored(MutableStateFlow(true), MutableStateFlow(Reachability.Unknown))
                }
                runCurrent()

                users.currentUser.value shouldBe rowan
                users.refreshCalls shouldBe 1
            }
        }

        test("a stored user is left alone") {
            runTest {
                val users = ServerUsers().apply { currentUser.value = rowan }
                backgroundScope.launch {
                    RestoreCurrentUser(users).keepRestored(MutableStateFlow(true), MutableStateFlow(Reachability.Reachable))
                }
                runCurrent()

                users.refreshCalls shouldBe 0
            }
        }

        test("an unreachable server waits, and the user is fetched once it is back") {
            runTest {
                val users = ServerUsers().apply { serverUser = rowan }
                val reachability = MutableStateFlow<Reachability>(Reachability.Unreachable)
                backgroundScope.launch { RestoreCurrentUser(users).keepRestored(MutableStateFlow(true), reachability) }
                runCurrent()
                users.refreshCalls shouldBe 0

                reachability.value = Reachability.Reachable
                runCurrent()

                users.currentUser.value shouldBe rowan
            }
        }

        test("a fetch that fails is tried again when the server comes back") {
            runTest {
                val users = ServerUsers()
                val reachability = MutableStateFlow<Reachability>(Reachability.Unknown)
                backgroundScope.launch { RestoreCurrentUser(users).keepRestored(MutableStateFlow(true), reachability) }
                runCurrent()
                users.refreshCalls shouldBe 1

                users.serverUser = rowan
                reachability.value = Reachability.Unreachable
                runCurrent()
                reachability.value = Reachability.Reachable
                runCurrent()

                users.currentUser.value shouldBe rowan
            }
        }

        test("signed out, nothing is fetched") {
            runTest {
                val users = ServerUsers().apply { serverUser = rowan }
                backgroundScope.launch {
                    RestoreCurrentUser(users).keepRestored(MutableStateFlow(false), MutableStateFlow(Reachability.Reachable))
                }
                runCurrent()

                users.refreshCalls shouldBe 0
            }
        }
    })

/** A user store with "a server" behind it: [serverUser] is what a refresh fetches and stores; null is a failed fetch. */
private class ServerUsers : UserRepository {
    val currentUser = MutableStateFlow<User?>(null)
    var serverUser: User? = null
    var refreshCalls = 0

    override fun observeCurrentUser(): Flow<User?> = currentUser

    override fun observeIsAdmin(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun getCurrentUser(): User? = currentUser.value

    override suspend fun saveUser(user: User) {
        currentUser.value = user
    }

    override suspend fun clearUsers() {
        currentUser.value = null
    }

    override suspend fun refreshCurrentUser(): User? {
        refreshCalls++
        serverUser?.let { currentUser.value = it }
        return serverUser
    }
}
