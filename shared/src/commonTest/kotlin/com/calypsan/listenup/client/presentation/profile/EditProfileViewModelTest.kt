package com.calypsan.listenup.client.presentation.profile

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.UserId
import com.calypsan.listenup.client.core.error.UnknownError
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private class TestFixture {
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

    private fun TestScope.createFixture(): TestFixture = TestFixture()

    private fun TestScope.keepStateHot(viewModel: EditProfileViewModel) {
        backgroundScope.launch { viewModel.state.collect { } }
    }

    private fun createUser(
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

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading before pipeline emits`() =
        runTest {
            val fixture = createFixture().apply { configure(currentUser = null) }
            val viewModel = fixture.build()
            // No keepStateHot here — stateIn with no subscriber returns the initial value.

            assertEquals(EditProfileUiState.Loading, viewModel.state.value)
        }

    @Test
    fun `state emits Error when no current user`() =
        runTest {
            val fixture = createFixture().apply { configure(currentUser = null) }
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            val err = assertIs<EditProfileUiState.Error>(viewModel.state.value)
            assertEquals("No user data available", err.message)
        }

    @Test
    fun `state emits Ready when user is present`() =
        runTest {
            val user = createUser()
            val fixture = createFixture().apply { configure(currentUser = user) }
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            val ready = assertIs<EditProfileUiState.Ready>(viewModel.state.value)
            assertEquals(user, ready.user)
            assertEquals(false, ready.isSaving)
        }

    @Test
    fun `Ready reflects cached avatar path when avatar is an image`() =
        runTest {
            val user = createUser(avatarType = "image", avatarValue = "avatar.jpg")
            val fixture = createFixture().apply { configure(currentUser = user, isAvatarCached = true) }
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            val ready = assertIs<EditProfileUiState.Ready>(viewModel.state.value)
            assertEquals("/cache/avatars/avatar.jpg", ready.localAvatarPath)
        }

    @Test
    fun `saveTagline success emits TaglineSaved and toggles isSaving`() =
        runTest {
            val user = createUser(tagline = null)
            val fixture =
                createFixture().apply {
                    configure(currentUser = user)
                    everySuspend { profileEditRepository.updateTagline(any()) } returns Success(Unit)
                }
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.saveTagline("new tagline")
                advanceUntilIdle()
                assertEquals(EditProfileEvent.TaglineSaved, awaitItem())
            }
            verifySuspend { fixture.profileEditRepository.updateTagline("new tagline") }
            assertEquals(false, (viewModel.state.value as EditProfileUiState.Ready).isSaving)
        }

    @Test
    fun `saveTagline normalizes empty to null and truncates at max length`() =
        runTest {
            val user = createUser()
            val fixture =
                createFixture().apply {
                    configure(currentUser = user)
                    everySuspend { profileEditRepository.updateTagline(any()) } returns Success(Unit)
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

    @Test
    fun `saveTagline failure emits SaveFailed`() =
        runTest {
            val user = createUser()
            val fixture =
                createFixture().apply {
                    configure(currentUser = user)
                    everySuspend { profileEditRepository.updateTagline(any()) } returns
                        AppResult.Failure(UnknownError(message = "db error", debugInfo = null))
                }
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.saveTagline("new")
                advanceUntilIdle()
                val event = assertIs<EditProfileEvent.SaveFailed>(awaitItem())
                assertEquals("Failed to save tagline", event.message)
            }
        }

    @Test
    fun `saveName success emits NameSaved`() =
        runTest {
            val user = createUser()
            val fixture =
                createFixture().apply {
                    configure(currentUser = user)
                    everySuspend { profileEditRepository.updateName(any(), any()) } returns Success(Unit)
                }
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.saveName("Bob", "Jones")
                advanceUntilIdle()
                assertEquals(EditProfileEvent.NameSaved, awaitItem())
            }
            verifySuspend { fixture.profileEditRepository.updateName("Bob", "Jones") }
        }

    @Test
    fun `uploadAvatar success emits AvatarUpdated`() =
        runTest {
            val user = createUser()
            val fixture =
                createFixture().apply {
                    configure(currentUser = user)
                    everySuspend { profileEditRepository.uploadAvatar(any(), any()) } returns Success(Unit)
                }
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.uploadAvatar(byteArrayOf(1, 2, 3), "image/jpeg")
                advanceUntilIdle()
                assertEquals(EditProfileEvent.AvatarUpdated, awaitItem())
            }
        }

    @Test
    fun `revertToAutoAvatar success emits AvatarUpdated`() =
        runTest {
            val user = createUser()
            val fixture =
                createFixture().apply {
                    configure(currentUser = user)
                    everySuspend { profileEditRepository.revertToAutoAvatar() } returns Success(Unit)
                }
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.revertToAutoAvatar()
                advanceUntilIdle()
                assertEquals(EditProfileEvent.AvatarUpdated, awaitItem())
            }
        }

    @Test
    fun `changePassword rejects passwords shorter than minimum`() =
        runTest {
            val user = createUser()
            val fixture = createFixture().apply { configure(currentUser = user) }
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.changePassword("short")
                advanceUntilIdle()
                val event = assertIs<EditProfileEvent.SaveFailed>(awaitItem())
                assertTrue(event.message.contains("$PASSWORD_MIN"))
            }
        }

    @Test
    fun `changePassword success emits PasswordChanged`() =
        runTest {
            val user = createUser()
            val fixture =
                createFixture().apply {
                    configure(currentUser = user)
                    everySuspend { profileEditRepository.changePassword(any()) } returns Success(Unit)
                }
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.changePassword("strongpass1")
                advanceUntilIdle()
                assertEquals(EditProfileEvent.PasswordChanged, awaitItem())
            }
            verifySuspend { fixture.profileEditRepository.changePassword("strongpass1") }
        }

    @Test
    fun `isSaving returns to false after save completes`() =
        runTest {
            // StateFlow conflates rapid true→false transitions, so we can't reliably
            // observe the intermediate isSaving=true state with a non-suspending mock.
            // Instead, verify the final state is unlocked for further edits.
            val user = createUser()
            val fixture =
                createFixture().apply {
                    configure(currentUser = user)
                    everySuspend { profileEditRepository.updateTagline(any()) } returns Success(Unit)
                }
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            viewModel.saveTagline("new")
            advanceUntilIdle()

            val ready = assertIs<EditProfileUiState.Ready>(viewModel.state.value)
            assertEquals(false, ready.isSaving)
        }

    @Test
    fun `state updates reactively when user changes`() =
        runTest {
            val user1 = createUser(displayName = "Before")
            val user2 = createUser(displayName = "After", updatedAtMs = 2000L)
            val fixture = createFixture().apply { configure(currentUser = user1) }
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()
            assertEquals("Before", (viewModel.state.value as EditProfileUiState.Ready).user.displayName)

            fixture.currentUserFlow.value = user2
            advanceUntilIdle()

            val ready = assertIs<EditProfileUiState.Ready>(viewModel.state.value)
            assertEquals("After", ready.user.displayName)
        }
}
