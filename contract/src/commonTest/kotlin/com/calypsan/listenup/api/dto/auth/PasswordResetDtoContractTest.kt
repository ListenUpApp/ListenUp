package com.calypsan.listenup.api.dto.auth

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Round-trips every reset DTO. [PasswordResetDecisionOutcome] is sealed — the exact shape
 * that broke iOS backup CI once before, so it is pinned here rather than assumed.
 */
class PasswordResetDtoContractTest :
    FunSpec({
        val json = contractJson

        test("PasswordResetTicket round-trips") {
            val original = PasswordResetTicket(ticketId = "t-1", expiresAt = 1_700_000_000_000)
            json.decodeFromString<PasswordResetTicket>(json.encodeToString(original)) shouldBe original
        }

        test("PasswordResetStatusEvent round-trips every status") {
            PasswordResetStatus.entries.forEach { status ->
                val original = PasswordResetStatusEvent(status = status, expiresAt = 1L)
                json.decodeFromString<PasswordResetStatusEvent>(json.encodeToString(original)) shouldBe original
            }
        }

        test("PasswordResetRequest round-trips") {
            val original =
                PasswordResetRequest(
                    id = "r-1",
                    userId = UserId("u-1"),
                    displayName = "Ada",
                    email = "ada@example.com",
                    requestedAt = 1L,
                    expiresAt = 2L,
                )
            json.decodeFromString<PasswordResetRequest>(json.encodeToString(original)) shouldBe original
        }

        test("PasswordResetDecisionOutcome round-trips both arms polymorphically") {
            val approved: PasswordResetDecisionOutcome = PasswordResetDecisionOutcome.Approved("ABCD-2345")
            val denied: PasswordResetDecisionOutcome = PasswordResetDecisionOutcome.Denied

            json.decodeFromString<PasswordResetDecisionOutcome>(json.encodeToString(approved)) shouldBe approved
            json.decodeFromString<PasswordResetDecisionOutcome>(json.encodeToString(denied)) shouldBe denied
        }
    })
