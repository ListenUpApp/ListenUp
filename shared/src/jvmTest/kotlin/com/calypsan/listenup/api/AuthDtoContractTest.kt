package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.PendingRegistrationToken
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.dto.auth.WeakPasswordReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AuthDtoContractTest : FunSpec({

    test("identifier value classes round-trip through JSON") {
        roundTrip(UserId("u-1")) shouldBe UserId("u-1")
        roundTrip(SessionId("s-1")) shouldBe SessionId("s-1")
        roundTrip(AccessToken("at-1")) shouldBe AccessToken("at-1")
        roundTrip(RefreshToken("rt-1")) shouldBe RefreshToken("rt-1")
        roundTrip(PendingRegistrationToken("pt-1")) shouldBe PendingRegistrationToken("pt-1")
    }

    test("user enums round-trip through JSON") {
        UserRole.entries.forEach { roundTrip(it) shouldBe it }
        UserStatus.entries.forEach { roundTrip(it) shouldBe it }
        WeakPasswordReason.entries.forEach { roundTrip(it) shouldBe it }
    }

    test("User survives JSON round-trip") {
        val user = User(
            id = UserId("u-1"),
            email = "alice@example.com",
            displayName = "Alice",
            role = UserRole.MEMBER,
            status = UserStatus.ACTIVE,
            createdAt = 1714694400000L,
        )
        roundTrip(user) shouldBe user
    }

    test("SessionSummary survives JSON round-trip") {
        val s = SessionSummary(
            id = SessionId("s-1"),
            label = "My iPhone",
            createdAt = 1L,
            lastUsedAt = 2L,
            current = true,
        )
        roundTrip(s) shouldBe s
    }

    test("LoginRequest enforces password length 8..1024") {
        LoginRequest(email = "a@b", password = "x".repeat(8))
        LoginRequest(email = "a@b", password = "x".repeat(1024))
        shouldThrow<IllegalArgumentException> {
            LoginRequest(email = "a@b", password = "x".repeat(7))
        }
        shouldThrow<IllegalArgumentException> {
            LoginRequest(email = "a@b", password = "x".repeat(1025))
        }
    }

    test("RegisterRequest enforces password length and non-blank displayName") {
        RegisterRequest(email = "a@b", password = "x".repeat(8), displayName = "Alice")
        shouldThrow<IllegalArgumentException> {
            RegisterRequest(email = "a@b", password = "x".repeat(8), displayName = "")
        }
        shouldThrow<IllegalArgumentException> {
            RegisterRequest(email = "a@b", password = "x".repeat(7), displayName = "Alice")
        }
    }

    test("auth requests round-trip through JSON") {
        val login = LoginRequest(
            email = "a@b",
            password = "x".repeat(8),
            pendingRegistrationToken = PendingRegistrationToken("pt"),
            sessionLabel = "phone",
        )
        val reg = RegisterRequest(
            email = "a@b",
            password = "x".repeat(8),
            displayName = "Alice",
            sessionLabel = null,
        )
        val refresh = RefreshRequest(refreshToken = RefreshToken("rt"))
        roundTrip(login) shouldBe login
        roundTrip(reg) shouldBe reg
        roundTrip(refresh) shouldBe refresh
    }
})

private inline fun <reified T : Any> roundTrip(value: T): T =
    Json.decodeFromString<T>(Json.encodeToString(value))
