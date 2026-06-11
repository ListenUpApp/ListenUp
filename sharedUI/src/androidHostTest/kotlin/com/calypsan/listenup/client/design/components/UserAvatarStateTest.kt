package com.calypsan.listenup.client.design.components

import com.calypsan.listenup.client.domain.model.CachedUserProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun profile(
    avatarType: String = "auto",
    displayName: String = "Ada Lovelace",
    avatarColor: String = "#3949AB",
) = CachedUserProfile(
    id = "u1",
    displayName = displayName,
    avatarType = avatarType,
    avatarValue = null,
    avatarColor = avatarColor,
)

class UserAvatarStateTest :
    FunSpec({
        test("null profile maps to Loading") {
            userAvatarUiState(profile = null, hasLocalAvatar = false, localPath = "/p", userId = "u1") shouldBe
                UserAvatarUiState.Loading
        }

        test("image type with a local file maps to Image with the avatar cache key") {
            val state =
                userAvatarUiState(
                    profile = profile(avatarType = "image", displayName = "Ada Lovelace"),
                    hasLocalAvatar = true,
                    localPath = "/avatars/u1.webp",
                    userId = "u1",
                )
            state.shouldBeInstanceOf<UserAvatarUiState.Image>()
            state.localPath shouldBe "/avatars/u1.webp"
            state.cacheKey shouldBe "u1-avatar"
            state.contentDescription shouldBe "Ada Lovelace"
        }

        test("image content description falls back when display name is blank") {
            val state =
                userAvatarUiState(
                    profile = profile(avatarType = "image", displayName = "  "),
                    hasLocalAvatar = true,
                    localPath = "/avatars/u1.webp",
                    userId = "u1",
                )
            state.shouldBeInstanceOf<UserAvatarUiState.Image>()
            state.contentDescription shouldBe "User avatar"
        }

        test("image type without a local file falls back to Initials") {
            userAvatarUiState(
                profile = profile(avatarType = "image"),
                hasLocalAvatar = false,
                localPath = "/p",
                userId = "u1",
            ).shouldBeInstanceOf<UserAvatarUiState.Initials>()
        }

        test("auto type maps to Initials regardless of local file") {
            userAvatarUiState(
                profile = profile(avatarType = "auto"),
                hasLocalAvatar = true,
                localPath = "/p",
                userId = "u1",
            ).shouldBeInstanceOf<UserAvatarUiState.Initials>()
        }

        test("avatarInitials takes the first letters of the first two words, uppercased") {
            avatarInitials("Ada Lovelace") shouldBe "AL"
        }

        test("avatarInitials handles a single name") {
            avatarInitials("Ada") shouldBe "A"
        }

        test("avatarInitials collapses extra whitespace") {
            avatarInitials("  Ada   Lovelace  ") shouldBe "AL"
        }

        test("avatarInitials returns ? for a blank name") {
            avatarInitials("   ") shouldBe "?"
        }
    })
