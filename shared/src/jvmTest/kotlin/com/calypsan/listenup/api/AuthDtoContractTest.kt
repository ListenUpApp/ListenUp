package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.PendingRegistrationToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
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
})

private inline fun <reified T : Any> roundTrip(value: T): T =
    Json.decodeFromString<T>(Json.encodeToString(value))
