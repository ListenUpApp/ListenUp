package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.test.fake.FakeUserRepository
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
class RestoreCurrentUserUseCaseTest :
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
                val users = FakeUserRepository().apply { serverUser = rowan }
                backgroundScope.launch {
                    RestoreCurrentUserUseCase(users).keepRestored(MutableStateFlow(true), MutableStateFlow(Reachability.Unknown))
                }
                runCurrent()

                users.currentUser.value shouldBe rowan
                users.refreshCalls shouldBe 1
            }
        }

        test("a stored user is left alone") {
            runTest {
                val users = FakeUserRepository().apply { currentUser.value = rowan }
                backgroundScope.launch {
                    RestoreCurrentUserUseCase(users).keepRestored(MutableStateFlow(true), MutableStateFlow(Reachability.Reachable))
                }
                runCurrent()

                users.refreshCalls shouldBe 0
            }
        }

        test("an unreachable server waits, and the user is fetched once it is back") {
            runTest {
                val users = FakeUserRepository().apply { serverUser = rowan }
                val reachability = MutableStateFlow<Reachability>(Reachability.Unreachable)
                backgroundScope.launch { RestoreCurrentUserUseCase(users).keepRestored(MutableStateFlow(true), reachability) }
                runCurrent()
                users.refreshCalls shouldBe 0

                reachability.value = Reachability.Reachable
                runCurrent()

                users.currentUser.value shouldBe rowan
            }
        }

        test("a fetch that fails is tried again when the server comes back") {
            runTest {
                val users = FakeUserRepository()
                val reachability = MutableStateFlow<Reachability>(Reachability.Unknown)
                backgroundScope.launch { RestoreCurrentUserUseCase(users).keepRestored(MutableStateFlow(true), reachability) }
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
                val users = FakeUserRepository().apply { serverUser = rowan }
                backgroundScope.launch {
                    RestoreCurrentUserUseCase(users).keepRestored(MutableStateFlow(false), MutableStateFlow(Reachability.Reachable))
                }
                runCurrent()

                users.refreshCalls shouldBe 0
            }
        }
    })
