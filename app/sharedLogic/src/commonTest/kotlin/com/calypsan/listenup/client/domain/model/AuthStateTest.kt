package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [AuthState] accessors that cross the Swift Export boundary.
 *
 * `PendingApproval.userId` is a `UserId` value class; the exported Swift wrapper has no
 * `.value` accessor, so iOS reads [AuthState.PendingApproval.userIdString] instead — the same
 * `idString` convention every other Swift-consumed domain model follows. Without it the iOS
 * observer fell back to an empty id, producing a malformed `registration-status//stream` URL.
 */
class AuthStateTest :
    FunSpec({

        test("PendingApproval.userIdString unwraps the value class to the raw id") {
            val pending = AuthState.PendingApproval(UserId("b4bebe72-f14f-4b71-9550-0d05e9119921"), "user@example.com")

            pending.userIdString shouldBe "b4bebe72-f14f-4b71-9550-0d05e9119921"
        }
    })
