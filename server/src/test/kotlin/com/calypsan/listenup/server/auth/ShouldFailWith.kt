package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.error.AuthError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Test helper. Asserts the suspending [block] throws an [AuthException]
 * wrapping a typed [AuthError] of the requested variant — the typical
 * pattern in `:server` tests.
 *
 * Returns the unwrapped error so callers can assert on its fields:
 *
 * ```
 * val err = shouldFailWith<AuthError.InvalidRefreshToken> { svc.refreshSession(req) }
 * err.familyRevoked shouldBe true
 * ```
 */
inline fun <reified E : AuthError> shouldFailWith(block: () -> Any?): E =
    shouldThrow<AuthException> { block() }.error.shouldBeInstanceOf<E>()
