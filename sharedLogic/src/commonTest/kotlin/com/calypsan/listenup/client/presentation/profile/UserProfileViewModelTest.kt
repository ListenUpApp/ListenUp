package com.calypsan.listenup.client.presentation.profile

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.PublicProfileDao
import com.calypsan.listenup.client.data.local.db.PublicProfileEntity
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.core.ShelfId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
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
class UserProfileViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun publicProfile(
            id: String,
            displayName: String = "Display",
            avatarType: String = "auto",
            tagline: String? = "a tagline",
            totalSecondsAllTime: Long = 0L,
            booksFinished: Int = 0,
            currentStreakDays: Int = 0,
            longestStreakDays: Int = 0,
            avatarUpdatedAt: Long = 0L,
        ): PublicProfileEntity =
            PublicProfileEntity(
                id = id,
                displayName = displayName,
                avatarType = avatarType,
                tagline = tagline,
                totalSecondsAllTime = totalSecondsAllTime,
                totalSecondsLast7Days = 0L,
                totalSecondsLast30Days = 0L,
                totalSecondsLast365Days = 0L,
                booksFinished = booksFinished,
                currentStreakDays = currentStreakDays,
                longestStreakDays = longestStreakDays,
                avatarUpdatedAt = avatarUpdatedAt,
            )

        fun user(
            id: String,
            displayName: String = "Display",
            tagline: String? = "a tagline",
            updatedAtMs: Long = 1_000L,
        ): User =
            User(
                id = UserId(id),
                email = "$id@example.com",
                displayName = displayName,
                firstName = null,
                lastName = null,
                isAdmin = false,
                tagline = tagline,
                createdAtMs = 0L,
                updatedAtMs = updatedAtMs,
            )

        fun shelf(
            id: String,
            name: String = "Shelf $id",
            ownerId: String,
            bookCount: Int = 3,
        ): Shelf =
            Shelf(
                id = ShelfId(id),
                name = name,
                description = null,
                isPrivate = false,
                ownerId = ownerId,
                ownerDisplayName = "Owner",
                bookCount = bookCount,
                totalDurationSeconds = 0L,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )

        class Fixture {
            val userRepository: UserRepository = mock()
            val publicProfileDao: PublicProfileDao = mock()
            val shelfRepository: ShelfRepository = mock()

            fun build(): UserProfileViewModel =
                UserProfileViewModel(
                    publicProfileDao = publicProfileDao,
                    shelfRepository = shelfRepository,
                    userRepository = userRepository,
                )
        }

        fun TestScope.keepHot(viewModel: UserProfileViewModel) {
            backgroundScope.launch { viewModel.state.collect { } }
        }

        test("own profile shows real stats from public_profiles row (not zeroed)") {
            runTest {
                val ownId = "me"
                val fixture = Fixture()
                val row =
                    publicProfile(
                        id = ownId,
                        displayName = "Me",
                        totalSecondsAllTime = 151_200,
                        booksFinished = 23,
                        currentStreakDays = 5,
                        longestStreakDays = 14,
                    )
                val ownUser = user(id = ownId, displayName = "Me", updatedAtMs = 4_242L)
                everySuspend { fixture.userRepository.getCurrentUser() } returns ownUser
                every { fixture.userRepository.observeCurrentUser() } returns MutableStateFlow(ownUser)
                every { fixture.publicProfileDao.observeById(ownId) } returns MutableStateFlow(row)
                every { fixture.shelfRepository.observeMyShelves(ownId) } returns
                    MutableStateFlow(
                        listOf(
                            shelf("s1", ownerId = ownId),
                            shelf("s2", ownerId = ownId),
                        ),
                    )

                val viewModel = fixture.build()
                keepHot(viewModel)

                viewModel.loadProfile(ownId)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<UserProfileUiState.Ready>()
                ready.userId shouldBe ownId
                ready.isOwnProfile shouldBe true
                ready.displayName shouldBe "Me"
                ready.totalListenTimeMs shouldBe 151_200_000L
                ready.booksFinished shouldBe 23
                ready.currentStreak shouldBe 5
                ready.longestStreak shouldBe 14
                ready.publicShelves.size shouldBe 2
                ready.recentBooks shouldBe emptyList()
            }
        }

        test("other profile assembles header+stats from Room and shelves from RPC") {
            runTest {
                val otherId = "other"
                val fixture = Fixture()
                val row =
                    publicProfile(
                        id = otherId,
                        displayName = "Bob",
                        totalSecondsAllTime = 7_200,
                        booksFinished = 9,
                        currentStreakDays = 2,
                        longestStreakDays = 7,
                    )
                everySuspend { fixture.userRepository.getCurrentUser() } returns user(id = "me")
                every { fixture.publicProfileDao.observeById(otherId) } returns MutableStateFlow(row)
                everySuspend { fixture.shelfRepository.getUserShelves(otherId) } returns
                    AppResult.Success(
                        listOf(
                            shelf("s1", ownerId = otherId),
                            shelf("s2", ownerId = otherId),
                        ),
                    )

                val viewModel = fixture.build()
                keepHot(viewModel)

                viewModel.loadProfile(otherId)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<UserProfileUiState.Ready>()
                ready.userId shouldBe otherId
                ready.isOwnProfile shouldBe false
                ready.displayName shouldBe "Bob"
                ready.totalListenTimeMs shouldBe 7_200_000L
                ready.booksFinished shouldBe 9
                ready.currentStreak shouldBe 2
                ready.longestStreak shouldBe 7
                ready.publicShelves.size shouldBe 2
                ready.recentBooks shouldBe emptyList()
            }
        }

        test("other profile renders header even when shelf RPC fails") {
            runTest {
                val otherId = "other"
                val fixture = Fixture()
                val row = publicProfile(id = otherId, displayName = "Bob", totalSecondsAllTime = 60)
                everySuspend { fixture.userRepository.getCurrentUser() } returns user(id = "me")
                every { fixture.publicProfileDao.observeById(otherId) } returns MutableStateFlow(row)
                everySuspend { fixture.shelfRepository.getUserShelves(otherId) } returns
                    AppResult.Failure(InternalError(debugInfo = "boom"))

                val viewModel = fixture.build()
                keepHot(viewModel)

                viewModel.loadProfile(otherId)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<UserProfileUiState.Ready>()
                ready.displayName shouldBe "Bob"
                ready.totalListenTimeMs shouldBe 60_000L
                ready.publicShelves shouldBe emptyList()
            }
        }

        test("stableAvatarColorHex is deterministic and from the palette") {
            val id = "a3f2b1c4-1234-4567-89ab-cdef01234567"
            val first = stableAvatarColorHex(id)
            val second = stableAvatarColorHex(id)
            first shouldBe second
            first shouldStartWith "#"
            first.length shouldBe 7
        }
    })
