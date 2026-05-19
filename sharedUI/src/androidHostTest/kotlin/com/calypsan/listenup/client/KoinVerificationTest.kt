package com.calypsan.listenup.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Characterization tests for [checkCriticalKoinBindings].
 *
 * These tests pin the fail-fast invariant that must survive the timing refactor in
 * [ListenUp.onCreate]: when any critical binding cannot be resolved, verification
 * throws [IllegalStateException] immediately.  The function is internal and operates
 * on plain lambdas, so no Koin graph is required.
 */
class KoinVerificationTest :
    FunSpec({
        test("passes when all resolvers succeed") {
            // Should not throw — every resolver returns without error.
            checkCriticalKoinBindings(
                listOf(
                    "A" to { Unit },
                    "B" to { Unit },
                ),
            )
        }

        test("throws IllegalStateException when a resolver fails") {
            shouldThrow<IllegalStateException> {
                checkCriticalKoinBindings(
                    listOf(
                        "ServerConfig" to { error("binding missing") },
                    ),
                )
            }
        }

        test("error message includes the binding name") {
            val ex =
                shouldThrow<IllegalStateException> {
                    checkCriticalKoinBindings(
                        listOf(
                            "PlaybackManager" to { error("no module") },
                        ),
                    )
                }
            ex.message shouldContain "PlaybackManager"
        }

        test("stops at the first failing resolver") {
            var secondResolved = false
            shouldThrow<IllegalStateException> {
                checkCriticalKoinBindings(
                    listOf(
                        "First" to { error("first fails") },
                        "Second" to { secondResolved = true },
                    ),
                )
            }
            // The second resolver must not have been invoked.
            secondResolved shouldBe false
        }

        test("passes an empty resolver list without throwing") {
            checkCriticalKoinBindings(emptyList())
        }
    })
