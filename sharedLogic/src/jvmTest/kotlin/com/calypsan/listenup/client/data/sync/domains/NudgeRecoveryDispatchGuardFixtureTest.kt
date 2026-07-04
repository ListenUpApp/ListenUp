package com.calypsan.listenup.client.data.sync.domains

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * The proof that the nudge-recovery completeness guard fires — [NudgeRecoveryDispatchGuard] exercised
 * on synthetic input. Only triggers with an EXPLICIT `when (domain.trigger)` arm count as handled; a
 * trigger routed only through the `else` warn-log must be reported as unhandled so a dropped nudge
 * that would silently never self-heal fails the build instead.
 */
class NudgeRecoveryDispatchGuardFixtureTest :
    FunSpec({

        // A synthetic runNudgeLifecycleRecovery that wires ONE trigger explicitly and drops every
        // other into the else warn-log — exactly the shape a forgotten new nudge domain produces.
        val syntheticEngine =
            """
            private suspend fun runNudgeLifecycleRecovery(domain: RefreshedDomain) {
                when (domain.trigger) {
                    SyncControl.ActiveSessionsChanged::class -> {
                        presenceRefreshSignal.ping()
                    }

                    else -> {
                        logger.warn {
                            "Nudge ${'$'}{domain.trigger.simpleName} declares a lifecycle recovery but the " +
                                "engine wires no action for it — a dropped frame will not self-heal."
                        }
                    }
                }
            }
            """.trimIndent()

        test("only triggers with an explicit non-else arm are reported as handled") {
            NudgeRecoveryDispatchGuard.handledTriggerNames(syntheticEngine) shouldBe
                setOf("ActiveSessionsChanged")
        }

        test("a trigger routed only through else is NOT counted as handled") {
            val handled = NudgeRecoveryDispatchGuard.handledTriggerNames(syntheticEngine)

            // ActivityChanged has no explicit arm — at runtime it would only warn-log; the guard must
            // flag its absence so the completeness spec goes red before it can ship.
            handled shouldContain "ActiveSessionsChanged"
            handled shouldNotContain "ActivityChanged"
        }

        test("multiple triggers sharing one arm are each reported as handled") {
            val shared =
                """
                private suspend fun runNudgeLifecycleRecovery(domain: RefreshedDomain) {
                    when (domain.trigger) {
                        SyncControl.ServerInfoChanged::class, SyncControl.PreferencesChanged::class -> Unit
                        else -> Unit
                    }
                }
                """.trimIndent()

            NudgeRecoveryDispatchGuard.handledTriggerNames(shared) shouldBe
                setOf("ServerInfoChanged", "PreferencesChanged")
        }
    })
