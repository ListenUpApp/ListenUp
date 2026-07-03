package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UserTest :
    FunSpec({
        fun user(
            avatarType: String,
            avatarValue: String?,
        ) = User(
            id = UserId("u1"),
            email = "a@b.c",
            displayName = "Ada Lovelace",
            isAdmin = false,
            avatarType = avatarType,
            avatarValue = avatarValue,
            createdAtMs = 0,
            updatedAtMs = 0,
        )

        test("hasImageAvatar is true for an image avatar even when avatarValue is null") {
            // avatarValue is architecturally dead (always null); image-ness is avatarType only.
            // The actual bytes are resolved by user id from local storage (see UserAvatar).
            user(avatarType = "image", avatarValue = null).hasImageAvatar shouldBe true
        }

        test("hasImageAvatar is false for an auto avatar") {
            user(avatarType = "auto", avatarValue = null).hasImageAvatar shouldBe false
        }
    })
