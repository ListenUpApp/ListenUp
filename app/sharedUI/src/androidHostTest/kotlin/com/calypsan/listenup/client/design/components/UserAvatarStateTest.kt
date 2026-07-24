package com.calypsan.listenup.client.design.components

import androidx.compose.ui.graphics.Color
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun profile(
    avatarType: String = "auto",
    displayName: String = "Ada Lovelace",
    updatedAt: Long = 100L,
) = CachedUserProfile(
    id = "u1",
    displayName = displayName,
    avatarType = avatarType,
    updatedAt = updatedAt,
)

class UserAvatarStateTest :
    FunSpec({
        test("null profile maps to Loading") {
            userAvatarUiState(profile = null, hasLocalAvatar = false, localPath = "/p", userId = "u1") shouldBe
                UserAvatarUiState.Loading
        }

        test("image type with a local file maps to Image with a version-stamped avatar cache key") {
            val state =
                userAvatarUiState(
                    profile = profile(avatarType = "image", displayName = "Ada Lovelace", updatedAt = 100L),
                    hasLocalAvatar = true,
                    localPath = "/avatars/u1.webp",
                    userId = "u1",
                )
            state.shouldBeInstanceOf<UserAvatarUiState.Image>()
            state.localPath shouldBe "/avatars/u1.webp"
            // The profile's updatedAt is folded into the key so a re-uploaded avatar (server bumps
            // updatedAt) busts the cached bitmap instead of rendering the stale one.
            state.cacheKey shouldBe "u1-avatar-100"
            state.contentDescription shouldBe "Ada Lovelace"
        }

        test("a changed updatedAt yields a different avatar cache key (busts the stale bitmap)") {
            fun keyAt(updatedAt: Long) =
                (
                    userAvatarUiState(
                        profile = profile(avatarType = "image", updatedAt = updatedAt),
                        hasLocalAvatar = true,
                        localPath = "/avatars/u1.webp",
                        userId = "u1",
                    ) as UserAvatarUiState.Image
                ).cacheKey
            keyAt(100L) shouldBe "u1-avatar-100"
            keyAt(200L) shouldBe "u1-avatar-200"
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

        // --- public_profiles-derived profiles (blank server hex; updatedAt = avatarUpdatedAt) ---

        test("a non-null public-profile-derived profile never maps to Loading") {
            userAvatarUiState(
                profile = profile(avatarType = "auto"),
                hasLocalAvatar = false,
                localPath = "/p",
                userId = "u1",
            ) shouldNotBe UserAvatarUiState.Loading
        }

        test("auto avatar yields Initials with a stable, non-transparent per-user color") {
            fun colorFor(userId: String) =
                (
                    userAvatarUiState(
                        profile = profile(avatarType = "auto"),
                        hasLocalAvatar = false,
                        localPath = "/p",
                        userId = userId,
                    ) as UserAvatarUiState.Initials
                ).color
            // Same user → same color (stable across recompositions), and fully opaque (not grey/unset).
            colorFor("u1") shouldBe colorFor("u1")
            colorFor("u1") shouldNotBe Color.Unspecified
            colorFor("u1").alpha shouldBe 1f
        }

        test("cache key folds updatedAt (= avatarUpdatedAt) for a public-profile-derived image avatar") {
            val state =
                userAvatarUiState(
                    profile = profile(avatarType = "image", updatedAt = 777L),
                    hasLocalAvatar = true,
                    localPath = "/avatars/u1.webp",
                    userId = "u1",
                )
            state.shouldBeInstanceOf<UserAvatarUiState.Image>()
            state.cacheKey shouldBe "u1-avatar-777"
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
