package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [AuthState] accessors that cross the Swift Export boundary, and for the
 * [AuthState.isInShell] classification the startup and navigation layers share.
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

        // isInShell is the single definition of "the authenticated shell hosts this state",
        // read by the navigation router and the startup readiness check. Both members of the
        // set are asserted here so a change to either is a deliberate, visible one.
        test("isInShell is true for the two states the authenticated shell hosts") {
            AuthState.Authenticated(UserId("user-001"), SessionId("session-001")).isInShell shouldBe true
            AuthState.SessionLapsed(UserId("user-001")).isInShell shouldBe true
        }

        // The complement, enumerated: every pre-shell state is routed by a login/setup flow,
        // never by the shell. Together with the test above this covers all AuthState subtypes.
        test("isInShell is false for every pre-shell state") {
            AuthState.Initializing.isInShell shouldBe false
            AuthState.NeedsServerUrl.isInShell shouldBe false
            AuthState.CheckingServer.isInShell shouldBe false
            AuthState.NeedsSetup.isInShell shouldBe false
            AuthState.NeedsLogin(openRegistration = false).isInShell shouldBe false
            AuthState.NeedsLogin(openRegistration = true).isInShell shouldBe false
            AuthState.PendingApproval(UserId("user-001"), "user@example.com").isInShell shouldBe false
        }
    })
