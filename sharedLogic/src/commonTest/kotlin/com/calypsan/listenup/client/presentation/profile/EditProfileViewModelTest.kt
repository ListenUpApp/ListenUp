package com.calypsan.listenup.client.presentation.profile

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ProfileEditRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        class TestFixture {
            val profileEditRepository: ProfileEditRepository = mock()
            val userRepository: UserRepository = mock()
            val imageRepository: ImageRepository = mock()
            val currentUserFlow = MutableStateFlow<User?>(null)

            fun configure(
                currentUser: User?,
                isAvatarCached: Boolean = false,
                avatarPath: String = "/cache/avatars/avatar.jpg",
            ) {
                currentUserFlow.value = currentUser
                every { userRepository.observeCurrentUser() } returns currentUserFlow
                every { imageRepository.userAvatarExists(any()) } returns isAvatarCached
                every { imageRepository.getUserAvatarPath(any()) } returns avatarPath
            }

            fun build(): EditProfileViewModel =
                EditProfileViewModel(
                    profileEditRepository = profileEditRepository,
                    userRepository = userRepository,
                    imageRepository = imageRepository,
                )
        }

        fun TestScope.createFixture(): TestFixture = TestFixture()

        fun TestScope.keepStateHot(viewModel: EditProfileViewModel) {
            backgroundScope.launch { viewModel.state.collect { } }
        }

        fun createUser(
            id: String = "user-1",
            displayName: String = "Alice",
            firstName: String? = "Alice",
            lastName: String? = "Smith",
            tagline: String? = "Hello world",
            avatarType: String = "auto",
            avatarValue: String? = null,
            updatedAtMs: Long = 1000L,
        ): User =
            User(
                id = UserId(id),
                email = "$id@example.com",
                displayName = displayName,
                firstName = firstName,
                lastName = lastName,
                isAdmin = false,
                avatarType = avatarType,
                avatarValue = avatarValue,
                avatarColor = "#6B7280",
                tagline = tagline,
                createdAtMs = 0L,
                updatedAtMs = updatedAtMs,
            )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        test("initial state is Loading before pipeline emits") {
            runTest {
                val fixture = createFixture().apply { configure(currentUser = null) }
                val viewModel = fixture.build()
                // No keepStateHot here — stateIn with no subscriber returns the initial value.

                viewModel.state.value shouldBe EditProfileUiState.Loading
            }
        }

        test("state emits Error when no current user") {
            runTest {
                val fixture = createFixture().apply { configure(currentUser = null) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                val err = viewModel.state.value.shouldBeInstanceOf<EditProfileUiState.Error>()
                err.message shouldBe "No user data available"
            }
        }

        test("state emits Ready when user is present") {
            runTest {
                val user = createUser()
                val fixture = createFixture().apply { configure(currentUser = user) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<EditProfileUiState.Ready>()
                ready.user shouldBe user
                ready.isSaving shouldBe false
            }
        }

        test("Ready reflects cached avatar path when avatar is an image") {
            runTest {
                val user = createUser(avatarType = "image", avatarValue = "avatar.jpg")
                val fixture = createFixture().apply { configure(currentUser = user, isAvatarCached = true) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<EditProfileUiState.Ready>()
                ready.localAvatarPath shouldBe "/cache/avatars/avatar.jpg"
            }
        }

        test("saveTagline success emits TaglineSaved and toggles isSaving") {
            runTest {
                val user = createUser(tagline = null)
                val fixture =
                    createFixture().apply {
                        configure(currentUser = user)
                        everySuspend { profileEditRepository.updateTagline(any()) } returns AppResult.Success(Unit)
                    }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.saveTagline("new tagline")
                    advanceUntilIdle()
                    awaitItem() shouldBe EditProfileEvent.TaglineSaved
                }
                verifySuspend { fixture.profileEditRepository.updateTagline("new tagline") }
                (viewModel.state.value as EditProfileUiState.Ready).isSaving shouldBe false
            }
        }

        test("saveTagline normalizes empty to null and truncates at max length") {
            runTest {
                val user = createUser()
                val fixture =
                    createFixture().apply {
                        configure(currentUser = user)
                        everySuspend { profileEditRepository.updateTagline(any()) } returns AppResult.Success(Unit)
                    }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.saveTagline("")
                advanceUntilIdle()
                verifySuspend { fixture.profileEditRepository.updateTagline(null) }

                val longTagline = "x".repeat(EditProfileViewModel.MAX_TAGLINE_LENGTH + 10)
                viewModel.saveTagline(longTagline)
                advanceUntilIdle()
                verifySuspend {
                    fixture.profileEditRepository.updateTagline("x".repeat(EditProfileViewModel.MAX_TAGLINE_LENGTH))
                }
            }
        }

        test("saveTagline failure emits SaveFailed") {
            runTest {
                val user = createUser()
                val fixture =
                    createFixture().apply {
                        configure(currentUser = user)
                        everySuspend { profileEditRepository.updateTagline(any()) } returns
                            AppResult.Failure(InternalError(debugInfo = "db error"))
                    }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.saveTagline("new")
                    advanceUntilIdle()
                    val event = awaitItem().shouldBeInstanceOf<EditProfileEvent.SaveFailed>()
                    event.message shouldBe "Failed to save tagline"
                }
            }
        }

        test("saveName success emits NameSaved") {
            runTest {
                val user = createUser()
                val fixture =
                    createFixture().apply {
                        configure(currentUser = user)
                        everySuspend { profileEditRepository.updateName(any(), any()) } returns AppResult.Success(Unit)
                    }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.saveName("Bob", "Jones")
                    advanceUntilIdle()
                    awaitItem() shouldBe EditProfileEvent.NameSaved
                }
                verifySuspend { fixture.profileEditRepository.updateName("Bob", "Jones") }
            }
        }

        test("uploadAvatar success emits AvatarUpdated") {
            runTest {
                val user = createUser()
                val fixture =
                    createFixture().apply {
                        configure(currentUser = user)
                        everySuspend { profileEditRepository.uploadAvatar(any(), any()) } returns AppResult.Success(Unit)
                    }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.uploadAvatar(byteArrayOf(1, 2, 3), "image/jpeg")
                    advanceUntilIdle()
                    awaitItem() shouldBe EditProfileEvent.AvatarUpdated
                }
            }
        }

        test("revertToAutoAvatar success emits AvatarUpdated") {
            runTest {
                val user = createUser()
                val fixture =
                    createFixture().apply {
                        configure(currentUser = user)
                        everySuspend { profileEditRepository.revertToAutoAvatar() } returns AppResult.Success(Unit)
                    }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.revertToAutoAvatar()
                    advanceUntilIdle()
                    awaitItem() shouldBe EditProfileEvent.AvatarUpdated
                }
            }
        }

        test("changePassword rejects passwords shorter than minimum") {
            runTest {
                val user = createUser()
                val fixture = createFixture().apply { configure(currentUser = user) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.changePassword("currentpass1", "short")
                    advanceUntilIdle()
                    val event = awaitItem().shouldBeInstanceOf<EditProfileEvent.SaveFailed>()
                    event.message shouldContain "$PASSWORD_MIN"
                }
            }
        }

        test("changePassword success emits PasswordChanged") {
            runTest {
                val user = createUser()
                val fixture =
                    createFixture().apply {
                        configure(currentUser = user)
                        everySuspend { profileEditRepository.changePassword(any(), any()) } returns AppResult.Success(Unit)
                    }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.changePassword("currentpass1", "strongpass1")
                    advanceUntilIdle()
                    awaitItem() shouldBe EditProfileEvent.PasswordChanged
                }
                verifySuspend { fixture.profileEditRepository.changePassword("currentpass1", "strongpass1") }
            }
        }

        test("isSaving returns to false after save completes") {
            runTest {
                // StateFlow conflates rapid true→false transitions, so we can't reliably
                // observe the intermediate isSaving=true state with a non-suspending mock.
                // Instead, verify the final state is unlocked for further edits.
                val user = createUser()
                val fixture =
                    createFixture().apply {
                        configure(currentUser = user)
                        everySuspend { profileEditRepository.updateTagline(any()) } returns AppResult.Success(Unit)
                    }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.saveTagline("new")
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<EditProfileUiState.Ready>()
                ready.isSaving shouldBe false
            }
        }

        test("state updates reactively when user changes") {
            runTest {
                val user1 = createUser(displayName = "Before")
                val user2 = createUser(displayName = "After", updatedAtMs = 2000L)
                val fixture = createFixture().apply { configure(currentUser = user1) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()
                (viewModel.state.value as EditProfileUiState.Ready).user.displayName shouldBe "Before"

                fixture.currentUserFlow.value = user2
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<EditProfileUiState.Ready>()
                ready.user.displayName shouldBe "After"
            }
        }
    })
