package com.calypsan.listenup.client.design.components

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * Pins the profile-less avatar fallback: a pending registrant has no server-side public
 * profile, so without a fallback name the avatar is stuck on the loading circle. With a name we
 * render initials; without one we keep the original loading behaviour for a profile still in flight.
 *
 * Plain JUnit4 (no Robolectric): [userAvatarUiState] is pure Kotlin with no Android framework deps.
 */
class UserAvatarUiStateTest {
    private val userId = "0c3dc41c-1361-4c6e-8638-3fe7671b6b22"

    @Test
    fun `no cached profile but a fallback name renders initials from that name`() {
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

    @Test
    fun `no cached profile and no fallback name stays on the loading placeholder`() {
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

    @Test
    fun `a blank fallback name does not mask the loading placeholder`() {
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
}
