package com.calypsan.listenup.client.presentation.profile

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.ProfileEditRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
            val userProfileRepository: UserProfileRepository = mock()
            val currentUserFlow = MutableStateFlow<User?>(null)
            val profileFlow = MutableStateFlow<CachedUserProfile?>(null)

            fun configure(currentUser: User?) {
                currentUserFlow.value = currentUser
                every { userRepository.observeCurrentUser() } returns currentUserFlow
                every { userProfileRepository.observeProfile(any()) } returns profileFlow
            }

            fun build(): EditProfileViewModel =
                EditProfileViewModel(
                    profileEditRepository = profileEditRepository,
                    userRepository = userRepository,
                    userProfileRepository = userProfileRepository,
                )
        }

        fun TestScope.createFixture(): TestFixture = TestFixture()

        /** Subscribe to state so stateIn(WhileSubscribed) stays hot throughout the test. */
        fun TestScope.keepStateHot(viewModel: EditProfileViewModel) {
            backgroundScope.launch { viewModel.state.collect { } }
        }

        fun createUser(
            id: String = "user-1",
            displayName: String = "Alice Smith",
            firstName: String? = "Alice",
            lastName: String? = "Smith",
            tagline: String? = "Hello world",
            updatedAtMs: Long = 1000L,
        ): User =
            User(
                id = UserId(id),
                email = "$id@example.com",
                displayName = displayName,
                firstName = firstName,
                lastName = lastName,
                isAdmin = false,
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

        // ── Initial state ─────────────────────────────────────────────────────

        test("initial state is Loading before pipeline emits") {
            runTest {
                val fixture = createFixture().apply { configure(currentUser = null) }
                val viewModel = fixture.build()

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

        test("state emits Ready when user is present with form seeded from user") {
            runTest {
                val user = createUser()
                val fixture = createFixture().apply { configure(currentUser = user) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<EditProfileUiState.Ready>()
                ready.user shouldBe user
                ready.firstName shouldBe "Alice"
                ready.lastName shouldBe "Smith"
                ready.tagline shouldBe "Hello world"
                ready.isSaving shouldBe false
                ready.isDirty shouldBe false
            }
        }

        test("state emits Ready (not stuck on Loading) when user has no name or tagline") {
            runTest {
                // Regression: a user whose first/last name and tagline are all null seeds a
                // FormState equal to the initial FormState(). The old code mutated formFlow to
                // that identical value and waited for a re-emission that StateFlow conflated
                // away — leaving the Edit Profile screen stuck on the Loading spinner forever.
                val user = createUser(displayName = "", firstName = null, lastName = null, tagline = null)
                val fixture = createFixture().apply { configure(currentUser = user) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<EditProfileUiState.Ready>()
                ready.firstName shouldBe ""
                ready.lastName shouldBe ""
                ready.tagline shouldBe ""
                ready.isDirty shouldBe false
            }
        }

        test("Ready seeds first/last from displayName when stored names are null") {
            runTest {
                val user = createUser(displayName = "Ada Lovelace", firstName = null, lastName = null)
                val fixture = createFixture().apply { configure(currentUser = user) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<EditProfileUiState.Ready>()
                ready.firstName shouldBe "Ada"
                ready.lastName shouldBe "Lovelace"
                ready.isDirty shouldBe false
            }
        }

        test("save() with derived names and no edits does not call updateProfile") {
            runTest {
                val user = createUser(displayName = "Ada Lovelace", firstName = null, lastName = null, tagline = null)
                val fixture = createFixture().apply { configure(currentUser = user) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.save()
                advanceUntilIdle()

                verifySuspend(dev.mokkery.verify.VerifyMode.not) {
                    fixture.profileEditRepository.updateProfile(any(), any(), any(), any())
                }
            }
        }

        // ── isDirty ───────────────────────────────────────────────────────────

        test("isDirty is false initially") {
            runTest {
                val user = createUser()
                val fixture = createFixture().apply { configure(currentUser = user) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<EditProfileUiState.Ready>()
                    .isDirty shouldBe false
            }
        }

        test("isDirty becomes true after setTagline changes the tagline") {
            runTest {
                val user = createUser(tagline = "original")
                val fixture = createFixture().apply { configure(currentUser = user) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.setTagline("updated tagline")
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<EditProfileUiState.Ready>()
                    .isDirty shouldBe true
            }
        }

        test("isDirty becomes true after any password field is set") {
            runTest {
                val user = createUser()
                val fixture = createFixture().apply { configure(currentUser = user) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.setNewPassword("somepass1")
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<EditProfileUiState.Ready>()
                    .isDirty shouldBe true
            }
        }

        test("isDirty becomes true after stageAvatarUpload") {
            runTest {
                val user = createUser()
                val fixture = createFixture().apply { configure(currentUser = user) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.stageAvatarUpload(byteArrayOf(1, 2, 3), "image/jpeg")
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<EditProfileUiState.Ready>()
                    .isDirty shouldBe true
            }
        }

        // ── save() — only tagline changed ─────────────────────────────────────

        test("save() with only tagline changed calls updateProfile(firstName=null, lastName=null, tagline, password=null)") {
            runTest {
                val user = createUser(tagline = "old")
                val fixture =
                    createFixture().apply {
                        configure(currentUser = user)
                        everySuspend {
                            profileEditRepository.updateProfile(
                                firstName = any(),
                                lastName = any(),
                                tagline = any(),
                                password = any(),
                            )
                        } returns AppResult.Success(Unit)
                    }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.setTagline("x")
                advanceUntilIdle()
                viewModel.save()
                advanceUntilIdle()

                verifySuspend {
                    fixture.profileEditRepository.updateProfile(
                        firstName = null,
                        lastName = null,
                        tagline = "x",
                        password = null,
                    )
                }
            }
        }

        // ── save() — staged avatar + name ─────────────────────────────────────

        test("save() with staged avatar upload calls uploadAvatar then updateProfile with name") {
            runTest {
                val user = createUser(firstName = "Alice", lastName = "Smith")
                val fixture =
                    createFixture().apply {
                        configure(currentUser = user)
                        everySuspend { profileEditRepository.uploadAvatar(any(), any()) } returns
                            AppResult.Success(Unit)
                        everySuspend {
                            profileEditRepository.updateProfile(any(), any(), any(), any())
                        } returns AppResult.Success(Unit)
                    }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.stageAvatarUpload(byteArrayOf(9, 8, 7), "image/jpeg")
                viewModel.setFirstName("Bob")
                advanceUntilIdle()
                viewModel.save()
                advanceUntilIdle()

                verifySuspend { fixture.profileEditRepository.uploadAvatar(any(), "image/jpeg") }
                verifySuspend {
                    fixture.profileEditRepository.updateProfile(
                        firstName = "Bob",
                        lastName = "Smith",
                        tagline = null,
                        password = null,
                    )
                }
            }
        }

        // ── save() — password validation ──────────────────────────────────────

        test("save() when newPassword != confirmPassword emits SaveFailed and does not call repo") {
            runTest {
                val user = createUser()
                val fixture = createFixture().apply { configure(currentUser = user) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.setCurrentPassword("currentpass1")
                viewModel.setNewPassword("newpass123")
                viewModel.setConfirmPassword("differentpass")
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.save()
                    advanceUntilIdle()
                    val event = awaitItem().shouldBeInstanceOf<EditProfileEvent.SaveFailed>()
                    event.message shouldBe "Passwords do not match."
                }

                // Repo must not have been called.
                verifySuspend(dev.mokkery.verify.VerifyMode.not) {
                    fixture.profileEditRepository.updateProfile(any(), any(), any(), any())
                }
            }
        }

        test("save() when newPassword is shorter than PASSWORD_MIN emits SaveFailed and does not call repo") {
            runTest {
                val user = createUser()
                val fixture = createFixture().apply { configure(currentUser = user) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.setCurrentPassword("currentpass1")
                viewModel.setNewPassword("short")
                viewModel.setConfirmPassword("short")
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.save()
                    advanceUntilIdle()
                    val event = awaitItem().shouldBeInstanceOf<EditProfileEvent.SaveFailed>()
                    event.message shouldBe "Password must be at least $PASSWORD_MIN characters."
                }

                verifySuspend(dev.mokkery.verify.VerifyMode.not) {
                    fixture.profileEditRepository.updateProfile(any(), any(), any(), any())
                }
            }
        }

        // ── save() — success / failure outcomes ───────────────────────────────

        test("save() success emits SaveSucceeded and clears password fields and avatarChange") {
            runTest {
                val user = createUser(tagline = "old")
                val fixture =
                    createFixture().apply {
                        configure(currentUser = user)
                        everySuspend {
                            profileEditRepository.updateProfile(any(), any(), any(), any())
                        } returns AppResult.Success(Unit)
                    }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.setTagline("new tagline")
                viewModel.setCurrentPassword("currentpass1")
                viewModel.setNewPassword("newstrongpass1")
                viewModel.setConfirmPassword("newstrongpass1")
                viewModel.stageAvatarUpload(byteArrayOf(1), "image/jpeg")

                // Avatar must be mocked too since we staged one.
                everySuspend { fixture.profileEditRepository.uploadAvatar(any(), any()) } returns
                    AppResult.Success(Unit)

                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.save()
                    advanceUntilIdle()
                    awaitItem() shouldBe EditProfileEvent.SaveSucceeded
                }

                val ready = viewModel.state.value.shouldBeInstanceOf<EditProfileUiState.Ready>()
                ready.currentPassword shouldBe ""
                ready.newPassword shouldBe ""
                ready.confirmPassword shouldBe ""
                ready.avatarChange shouldBe AvatarChange.None
                ready.isSaving shouldBe false
            }
        }

        test("save() failure emits SaveFailed and keeps form intact") {
            runTest {
                val user = createUser(tagline = "old")
                val fixture =
                    createFixture().apply {
                        configure(currentUser = user)
                        everySuspend {
                            profileEditRepository.updateProfile(any(), any(), any(), any())
                        } returns AppResult.Failure(InternalError(debugInfo = "server error"))
                    }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.setTagline("new tagline")
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.save()
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<EditProfileEvent.SaveFailed>()
                }

                // Form is intact — tagline still shows the edited value.
                val ready = viewModel.state.value.shouldBeInstanceOf<EditProfileUiState.Ready>()
                ready.tagline shouldBe "new tagline"
                ready.isSaving shouldBe false
            }
        }

        // ── setTagline truncation ─────────────────────────────────────────────

        test("setTagline truncates at MAX_TAGLINE_LENGTH") {
            runTest {
                val user = createUser()
                val fixture = createFixture().apply { configure(currentUser = user) }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                val longTagline = "x".repeat(EditProfileViewModel.MAX_TAGLINE_LENGTH + 10)
                viewModel.setTagline(longTagline)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<EditProfileUiState.Ready>()
                ready.tagline shouldBe "x".repeat(EditProfileViewModel.MAX_TAGLINE_LENGTH)
            }
        }

        // ── isSaving ─────────────────────────────────────────────────────────

        test("isSaving returns to false after save completes") {
            runTest {
                val user = createUser(tagline = "old")
                val fixture =
                    createFixture().apply {
                        configure(currentUser = user)
                        everySuspend {
                            profileEditRepository.updateProfile(any(), any(), any(), any())
                        } returns AppResult.Success(Unit)
                    }
                val viewModel = fixture.build()
                keepStateHot(viewModel)
                advanceUntilIdle()

                viewModel.setTagline("new")
                advanceUntilIdle()
                viewModel.save()
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<EditProfileUiState.Ready>()
                    .isSaving shouldBe false
            }
        }
    })
