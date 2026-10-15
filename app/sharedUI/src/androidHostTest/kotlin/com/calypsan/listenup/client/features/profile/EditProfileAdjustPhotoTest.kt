package com.calypsan.listenup.client.features.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.design.image.decodePhotosWithBitmapFactory
import com.calypsan.listenup.client.design.image.pngBytes
import com.calypsan.listenup.client.design.image.pngFile
import com.calypsan.listenup.client.design.image.restoreCoil
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.ProfileEditRepository
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.profile.AvatarChange
import com.calypsan.listenup.client.presentation.profile.EditProfileUiState
import com.calypsan.listenup.client.presentation.profile.EditProfileViewModel
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Edit profile frames the avatar inside the form (Q2): a pick goes straight into the editor and Done
 * stages it with its frame; Adjust photo re-frames the photo you have; and a pick that cannot be read
 * says so instead of being dropped. The editor previews the profile scallop (R4).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditProfileAdjustPhotoTest {
    @get:Rule
    val compose = createComposeRule()

    private var pickedAgain = false
    private var pickConsumed = false

    @After
    fun tearDown() {
        stopKoin()
        restoreCoil()
    }

    private fun editProfileViewModel(hasImageAvatar: Boolean): EditProfileViewModel {
        val user =
            User(
                id = UserId("u1"),
                email = "maya@example.com",
                displayName = "Maya Okafor",
                firstName = "Maya",
                lastName = "Okafor",
                isAdmin = false,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        val profiles =
            mock<UserProfileRepository>(MockMode.autoUnit) {
                every { observeProfile(any()) } returns
                    flowOf(CachedUserProfile("u1", "Maya Okafor", if (hasImageAvatar) "image" else "auto", 1L))
            }
        val avatarFile = pngFile()
        startKoin {
            modules(
                module {
                    single<UserProfileRepository> { profiles }
                    single<ImageStorage> {
                        mock(MockMode.autoUnit) {
                            every { getUserAvatarPath(any()) } returns avatarFile.absolutePath
                            every { userAvatarExists(any()) } returns true
                        }
                    }
                    single<ImageRepository> {
                        mock(MockMode.autoUnit) {
                            everySuspend { downloadUserAvatar(any(), any()) } returns AppResult.Success(false)
                        }
                    }
                },
            )
        }
        decodePhotosWithBitmapFactory()
        return EditProfileViewModel(
            profileEditRepository = mock<ProfileEditRepository>(MockMode.autoUnit),
            userRepository = mock<UserRepository>(MockMode.autoUnit) { every { observeCurrentUser() } returns flowOf(user) },
            userProfileRepository = profiles,
        )
    }

    private fun show(
        vm: EditProfileViewModel,
        pick: AvatarPick? = null,
    ) = compose.setContent {
        MaterialTheme {
            EditProfileScreen(
                onBack = {},
                onPickImage = { pickedAgain = true },
                pickResult = pick,
                onPickConsumed = { pickConsumed = true },
                viewModel = vm,
            )
        }
    }

    private fun EditProfileViewModel.readyState() = state.value.shouldBeInstanceOf<EditProfileUiState.Ready>()

    private fun pressDone() {
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { compose.onNodeWithText("Done").assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
    }

    private fun awaitText(text: String) =
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `with an image avatar, Adjust photo opens the editor and Done stages a reframe`() {
        val vm = editProfileViewModel(hasImageAvatar = true)
        show(vm)
        awaitText("Adjust photo")
        compose.onNodeWithText("Adjust photo").performClick()
        pressDone()
        vm.readyState().avatarChange.shouldBeInstanceOf<AvatarChange.Reframe>()
        vm.readyState().isDirty shouldBe true
    }

    @Test
    fun `with no image avatar there is nothing to adjust`() {
        val vm = editProfileViewModel(hasImageAvatar = false)
        show(vm)
        awaitText("Upload photo")
        compose.onNodeWithText("Adjust photo").assertDoesNotExist()
    }

    @Test
    fun `a picked photo goes into the editor and Done stages it with its frame`() {
        val vm = editProfileViewModel(hasImageAvatar = false)
        show(vm, pick = AvatarPick.Picked(pngBytes()))
        pressDone()
        vm
            .readyState()
            .avatarChange
            .shouldBeInstanceOf<AvatarChange.Upload>()
            .frame
            .shouldNotBeNull()
        pickConsumed shouldBe true
    }

    @Test
    fun `a pick that cannot be read says so and offers another`() {
        val vm = editProfileViewModel(hasImageAvatar = false)
        show(vm, pick = AvatarPick.Undecodable)
        awaitText("Couldn't open this photo")
        compose.onNodeWithText("Choose another").performClick()
        pickedAgain shouldBe true
        vm.readyState().avatarChange shouldBe AvatarChange.None
    }

    @Test
    fun `the avatar editor previews the profile scallop`() {
        val vm = editProfileViewModel(hasImageAvatar = false)
        show(vm, pick = AvatarPick.Picked(pngBytes()))
        awaitText("Profile and lists")
    }
}
