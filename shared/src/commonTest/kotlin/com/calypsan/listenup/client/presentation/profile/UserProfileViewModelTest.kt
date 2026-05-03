package com.calypsan.listenup.client.presentation.profile

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.core.error.UnknownError
import com.calypsan.listenup.client.domain.model.ProfileRecentBook
import com.calypsan.listenup.client.domain.model.ProfileShelfSummary
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.model.UserProfile
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.profile.LoadUserProfileUseCase
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

@OptIn(ExperimentalCoroutinesApi::class)
class UserProfileViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private class TestFixture {
        val userRepository: UserRepository = mock()
        val imageRepository: ImageRepository = mock()
        val loadUserProfileUseCase: LoadUserProfileUseCase = mock()
        val currentUserFlow = MutableStateFlow<User?>(null)

        fun configure(
            currentUser: User?,
            isAvatarCached: Boolean = false,
            avatarPath: String = "/cache/avatars/avatar.jpg",
            bookCoverPath: String = "/cache/covers/book.jpg",
            isBookCoverCached: Boolean = false,
        ) {
            currentUserFlow.value = currentUser
            every { userRepository.observeCurrentUser() } returns currentUserFlow
            everySuspend { userRepository.getCurrentUser() } returns currentUser
            every { imageRepository.userAvatarExists(any()) } returns isAvatarCached
            every { imageRepository.getUserAvatarPath(any()) } returns avatarPath
            every { imageRepository.bookCoverExists(any()) } returns isBookCoverCached
            every { imageRepository.getBookCoverPath(any()) } returns bookCoverPath
        }

        fun build(): UserProfileViewModel =
            UserProfileViewModel(
                loadUserProfileUseCase = loadUserProfileUseCase,
                userRepository = userRepository,
                imageRepository = imageRepository,
            )
    }

    private fun TestScope.createFixture(): TestFixture = TestFixture()

    private fun TestScope.keepStateHot(viewModel: UserProfileViewModel) {
        backgroundScope.launch { viewModel.state.collect { } }
    }

    private fun createUser(
        id: String = "user-1",
        displayName: String = "Alice",
        avatarType: String = "auto",
        avatarValue: String? = null,
        tagline: String? = "hello",
        updatedAtMs: Long = 1000L,
    ): User =
        User(
            id = UserId(id),
            email = "$id@example.com",
            displayName = displayName,
            firstName = null,
            lastName = null,
            isAdmin = false,
            avatarType = avatarType,
            avatarValue = avatarValue,
            avatarColor = "#6B7280",
            tagline = tagline,
            createdAtMs = 0L,
            updatedAtMs = updatedAtMs,
        )

    private fun createProfile(
        userId: String = "other-1",
        displayName: String = "Bob",
        avatarType: String = "auto",
        avatarValue: String? = null,
        totalListenTimeMs: Long = 3_600_000L,
        booksFinished: Int = 5,
        recentBooks: List<ProfileRecentBook> = emptyList(),
        publicShelves: List<ProfileShelfSummary> = emptyList(),
    ): UserProfile =
        UserProfile(
            userId = userId,
            displayName = displayName,
            avatarType = avatarType,
            avatarValue = avatarValue,
            avatarColor = "#6B7280",
            tagline = null,
            totalListenTimeMs = totalListenTimeMs,
            booksFinished = booksFinished,
            currentStreak = 0,
            longestStreak = 0,
            recentBooks = recentBooks,
            publicShelves = publicShelves,
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
    fun `initial state is Idle`() =
        runTest {
            val fixture = createFixture().apply { configure(currentUser = null) }
            val viewModel = fixture.build()
            keepStateHot(viewModel)
            advanceUntilIdle()

            assertEquals(UserProfileUiState.Idle, viewModel.state.value)
        }

    @Test
    fun `loadProfile for own user emits Ready with isOwnProfile true and stats zeroed`() =
        runTest {
            val user = createUser(id = "me", displayName = "Me", tagline = "taglined")
            val fixture = createFixture().apply { configure(currentUser = user) }
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            viewModel.loadProfile("me")
            advanceUntilIdle()

            val ready = assertIs<UserProfileUiState.Ready>(viewModel.state.value)
            assertEquals("me", ready.userId)
            assertEquals(true, ready.isOwnProfile)
            assertEquals("Me", ready.displayName)
            assertEquals("taglined", ready.tagline)
            assertEquals(0L, ready.totalListenTimeMs)
            assertEquals(0, ready.booksFinished)
            assertEquals(emptyList(), ready.recentBooks)
            assertEquals(emptyList(), ready.publicShelves)
        }

    @Test
    fun `own profile updates reactively when local user changes`() =
        runTest {
            val user1 = createUser(id = "me", displayName = "Old Name")
            val user2 = createUser(id = "me", displayName = "New Name", updatedAtMs = 2000L)
            val fixture = createFixture().apply { configure(currentUser = user1) }
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            viewModel.loadProfile("me")
            advanceUntilIdle()
            assertEquals("Old Name", (viewModel.state.value as UserProfileUiState.Ready).displayName)

            fixture.currentUserFlow.value = user2
            advanceUntilIdle()

            val ready = assertIs<UserProfileUiState.Ready>(viewModel.state.value)
            assertEquals("New Name", ready.displayName)
            assertEquals(2000L, ready.avatarCacheBuster)
        }

    @Test
    fun `own profile with image avatar exposes local path when cached`() =
        runTest {
            val user = createUser(avatarType = "image", avatarValue = "avatar.jpg")
            val fixture = createFixture().apply { configure(currentUser = user, isAvatarCached = true) }
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            viewModel.loadProfile(user.id.value)
            advanceUntilIdle()

            val ready = assertIs<UserProfileUiState.Ready>(viewModel.state.value)
            assertEquals("/cache/avatars/avatar.jpg", ready.localAvatarPath)
        }

    @Test
    fun `loadProfile for other user emits Ready with server stats`() =
        runTest {
            val profile =
                createProfile(
                    userId = "other-1",
                    displayName = "Bob",
                    totalListenTimeMs = 7_200_000L,
                    booksFinished = 12,
                )
            val fixture =
                createFixture().apply {
                    configure(currentUser = null)
                    everySuspend { loadUserProfileUseCase(any()) } returns Success(profile)
                }
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            viewModel.loadProfile("other-1")
            advanceUntilIdle()

            val ready = assertIs<UserProfileUiState.Ready>(viewModel.state.value)
            assertEquals("other-1", ready.userId)
            assertEquals(false, ready.isOwnProfile)
            assertEquals("Bob", ready.displayName)
            assertEquals(7_200_000L, ready.totalListenTimeMs)
            assertEquals(12, ready.booksFinished)
        }

    @Test
    fun `loadProfile for other user with uncached avatar downloads and refines Ready`() =
        runTest {
            val profile = createProfile(avatarType = "image", avatarValue = "avatar.jpg")
            val fixture =
                createFixture().apply {
                    configure(currentUser = null, isAvatarCached = false)
                    everySuspend { loadUserProfileUseCase(any()) } returns Success(profile)
                    everySuspend { imageRepository.downloadUserAvatar(any(), any()) } returns Success(true)
                }
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            viewModel.loadProfile(profile.userId)
            advanceUntilIdle()

            val ready = assertIs<UserProfileUiState.Ready>(viewModel.state.value)
            assertEquals("/cache/avatars/avatar.jpg", ready.localAvatarPath)
            verifySuspend { fixture.imageRepository.downloadUserAvatar(profile.userId, false) }
        }

    @Test
    fun `loadProfile for other user failure emits Error`() =
        runTest {
            val fixture =
                createFixture().apply {
                    configure(currentUser = null)
                    everySuspend { loadUserProfileUseCase(any()) } returns
                        AppResult.Failure(UnknownError(message = "nope", debugInfo = null))
                }
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            viewModel.loadProfile("other-1")
            advanceUntilIdle()

            val err = assertIs<UserProfileUiState.Error>(viewModel.state.value)
            assertEquals("Failed to load profile", err.message)
        }

    @Test
    fun `loadProfile maps recent book covers to local paths when cached`() =
        runTest {
            val profile =
                createProfile(
                    recentBooks =
                        listOf(
                            ProfileRecentBook(bookId = "book-1", title = "Book One", coverPath = "remote.jpg"),
                        ),
                )
            val fixture =
                createFixture().apply {
                    configure(currentUser = null, isBookCoverCached = true, bookCoverPath = "/local/book-1.jpg")
                    everySuspend { loadUserProfileUseCase(any()) } returns Success(profile)
                }
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            viewModel.loadProfile("other-1")
            advanceUntilIdle()

            val ready = assertIs<UserProfileUiState.Ready>(viewModel.state.value)
            assertEquals(1, ready.recentBooks.size)
            assertEquals("/local/book-1.jpg", ready.recentBooks.first().coverPath)
        }

    @Test
    fun `loadProfile ignores redundant call for same userId`() =
        runTest {
            val profile = createProfile()
            val fixture =
                createFixture().apply {
                    configure(currentUser = null)
                    everySuspend { loadUserProfileUseCase(any()) } returns Success(profile)
                }
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            viewModel.loadProfile("other-1")
            advanceUntilIdle()
            viewModel.loadProfile("other-1")
            advanceUntilIdle()

            // Should only fire once: the second loadProfile with same id & forceRefresh=false is a no-op.
            verifySuspend { fixture.loadUserProfileUseCase("other-1") }
        }

    @Test
    fun `refresh re-runs pipeline even for same userId`() =
        runTest {
            val profile = createProfile()
            val fixture =
                createFixture().apply {
                    configure(currentUser = null)
                    everySuspend { loadUserProfileUseCase(any()) } returns Success(profile)
                }
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            viewModel.loadProfile("other-1")
            advanceUntilIdle()
            viewModel.refresh()
            advanceUntilIdle()

            verifySuspend(
                mode =
                    dev.mokkery.verify.VerifyMode
                        .exactly(2),
            ) {
                fixture.loadUserProfileUseCase("other-1")
            }
        }

    @Test
    fun `loadProfile for non-existent own user returns Error`() =
        runTest {
            val fixture = createFixture().apply { configure(currentUser = null) }
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            // currentUser is null → isOwn = false → falls through to other-user fetch → no mock → test expects failure
            everySuspend { fixture.loadUserProfileUseCase(any()) } returns
                AppResult.Failure(UnknownError(message = "nope", debugInfo = null))

            viewModel.loadProfile("ghost")
            advanceUntilIdle()

            assertIs<UserProfileUiState.Error>(viewModel.state.value)
        }

    @Test
    fun `own profile with null user in observe emits Error`() =
        runTest {
            val user = createUser()
            val fixture = createFixture().apply { configure(currentUser = user) }
            val viewModel = fixture.build()
            keepStateHot(viewModel)

            viewModel.loadProfile(user.id.value)
            advanceUntilIdle()
            assertIs<UserProfileUiState.Ready>(viewModel.state.value)

            fixture.currentUserFlow.value = null
            advanceUntilIdle()

            val err = assertIs<UserProfileUiState.Error>(viewModel.state.value)
            assertEquals("No user data available", err.message)
        }
}
