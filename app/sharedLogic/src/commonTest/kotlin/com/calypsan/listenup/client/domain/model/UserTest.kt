package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UserTest :
    FunSpec({
        fun user(
            displayName: String = "Ada Lovelace",
            firstName: String? = null,
            lastName: String? = null,
        ) = User(
            id = UserId("u1"),
            email = "a@b.c",
            displayName = displayName,
            firstName = firstName,
            lastName = lastName,
            isAdmin = false,
            createdAtMs = 0,
            updatedAtMs = 0,
        )

        test("initials takes the first letter of the first two name parts") {
            user(displayName = "Ada Lovelace").initials shouldBe "AL"
        }

        test("initials falls back to a single letter for a one-word name") {
            user(displayName = "Grace").initials shouldBe "G"
        }

        test("fullName prefers first + last name over displayName") {
            user(displayName = "ada_l", firstName = "Ada", lastName = "Lovelace").fullName shouldBe "Ada Lovelace"
        }

        test("fullName falls back to displayName when no name parts are set") {
            user(displayName = "Ada Lovelace").fullName shouldBe "Ada Lovelace"
        }
    })
