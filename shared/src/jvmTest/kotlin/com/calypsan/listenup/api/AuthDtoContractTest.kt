package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.PendingRegistrationToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.dto.auth.WeakPasswordReason
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
})

private inline fun <reified T : Any> roundTrip(value: T): T =
    Json.decodeFromString<T>(Json.encodeToString(value))
