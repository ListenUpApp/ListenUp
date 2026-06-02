package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

class ProfileContractTest :
    FunSpec({
        test("Profile round-trips through JSON") {
            val p = Profile(UserId("u1"), "Pat", "Reader", "image", 123L)
            val json = contractJson.encodeToString(p)
            contractJson.decodeFromString<Profile>(json) shouldBe p
        }

        test("UpdateProfileRequest round-trips") {
            val r =
                UpdateProfileRequest(
                    displayName = "Pat",
                    tagline = "hi",
                    avatarType = "auto",
                    password = PasswordChange("old12345", "new12345"),
                )
            val json = contractJson.encodeToString(r)
            contractJson.decodeFromString<UpdateProfileRequest>(json) shouldBe r
        }

        test("PasswordChange rejects a too-short new password") {
            shouldThrow<IllegalArgumentException> { PasswordChange("old12345", "short") }
        }

        test("UpdateProfileRequest rejects an unknown avatarType") {
            shouldThrow<IllegalArgumentException> { UpdateProfileRequest(avatarType = "bogus") }
        }
    })
