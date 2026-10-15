package com.calypsan.listenup.client.design.components

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pins the profile-less avatar fallback: a pending registrant has no server-side public
 * profile, so without a fallback name the avatar is stuck on the loading circle. With a name we
 * render initials; without one we keep the original loading behaviour for a profile still in flight.
 * Also pins that an image avatar carries its frame to the renderer.
 *
 * Pure (no Robolectric): [userAvatarUiState] is plain Kotlin with no Android framework deps.
 */
class UserAvatarUiStateTest :
    FunSpec({
        val userId = "0c3dc41c-1361-4c6e-8638-3fe7671b6b22"

        fun profile(
            avatarType: String = "auto",
            avatarFrame: ImageFrame? = null,
        ) = CachedUserProfile(
            id = userId,
            displayName = "Darlene Hull",
            avatarType = avatarType,
            updatedAt = 1L,
            avatarFrame = avatarFrame,
        )

        test("no cached profile but a fallback name renders initials from that name") {
            val state =
                userAvatarUiState(
                    profile = null,
                    hasLocalAvatar = false,
                    localPath = "",
                    userId = userId,
                    fallbackName = "Darlene Hull",
                )

            state.shouldBeInstanceOf<UserAvatarUiState.Initials>().initials shouldBe "DH"
        }

        test("no cached profile and no fallback name stays on the loading placeholder") {
            val state =
                userAvatarUiState(
                    profile = null,
                    hasLocalAvatar = false,
                    localPath = "",
                    userId = userId,
                    fallbackName = null,
                )

            state shouldBe UserAvatarUiState.Loading
        }

        test("a blank fallback name does not mask the loading placeholder") {
            val state =
                userAvatarUiState(
                    profile = null,
                    hasLocalAvatar = false,
                    localPath = "",
                    userId = "u1",
                    fallbackName = "   ",
                )

            state shouldBe UserAvatarUiState.Loading
        }

        test("an image avatar carries its frame to the renderer") {
            val frame = ImageFrame(focusX = 0.5, focusY = 0.25, zoom = 2.0)
            val state =
                userAvatarUiState(
                    profile = profile(avatarType = "image", avatarFrame = frame),
                    hasLocalAvatar = true,
                    localPath = "/tmp/a.jpg",
                    userId = userId,
                )

            state.shouldBeInstanceOf<UserAvatarUiState.Image>().frame shouldBe frame
        }

        test("an image avatar with no frame renders today's centre crop") {
            val state = userAvatarUiState(profile(avatarType = "image"), true, "/tmp/a.jpg", userId)

            state.shouldBeInstanceOf<UserAvatarUiState.Image>().frame shouldBe null
        }
    })
