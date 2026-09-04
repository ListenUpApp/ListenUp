package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Proof that each sub-rule of [ControlChannelIsNotADataPathRule] fires on a synthetic violation
 * — the detection is pure ([ControlChannelDetector]) so it can be driven directly.
 */
class ControlChannelIsNotADataPathRuleFixtureTest :
    FunSpec({

        context("sub-rule 1 — control call-site allowlist") {
            test("a NEW file emitting a control frame is reported as an offender") {
                val offenders =
                    ControlChannelDetector.controlCallSiteOffenders(
                        fileName = "RogueService.kt",
                        source = "fun leak() { bus.broadcastControl(SyncControl.ActivityChanged) }",
                        allowlist = ControlChannelDetector.CONTROL_CALL_SITES,
                    )
                offenders.size shouldBe 1
                offenders.first() shouldContain "not on the"
            }

            test("an allowlisted file emitting a DIFFERENT frame than it is cleared for offends") {
                // AdminSettingsServiceImpl is cleared for ServerInfoChanged only; LibraryDataChanged is new.
                val offenders =
                    ControlChannelDetector.controlCallSiteOffenders(
                        fileName = "AdminSettingsServiceImpl.kt",
                        source = "fun x() { bus.broadcastControl(SyncControl.LibraryDataChanged) }",
                        allowlist = ControlChannelDetector.CONTROL_CALL_SITES,
                    )
                offenders.size shouldBe 1
            }

            test("an allowlisted (file, frame) pair is clean") {
                ControlChannelDetector
                    .controlCallSiteOffenders(
                        fileName = "AdminSettingsServiceImpl.kt",
                        source = "fun x() { bus.broadcastControl(SyncControl.ServerInfoChanged) }",
                        allowlist = ControlChannelDetector.CONTROL_CALL_SITES,
                    ).shouldBeEmpty()
            }

            test("a control emission buried in a comment does not trigger") {
                ControlChannelDetector
                    .controlCallSiteOffenders(
                        fileName = "RogueService.kt",
                        source = "// example: bus.broadcastControl(SyncControl.ActivityChanged)",
                        allowlist = ControlChannelDetector.CONTROL_CALL_SITES,
                    ).shouldBeEmpty()
            }

            test("a multi-line publishControl call site is detected across the newline") {
                val offenders =
                    ControlChannelDetector.controlCallSiteOffenders(
                        fileName = "RogueService.kt",
                        source =
                            """
                            fun leak() {
                                bus.publishControl(
                                    SyncControl.UserDeleted(reason = "x"),
                                    id.value,
                                )
                            }
                            """.trimIndent(),
                        allowlist = ControlChannelDetector.CONTROL_CALL_SITES,
                    )
                offenders.size shouldBe 1
            }
        }

        context("sub-rule 2 — FirehoseSuppressed pairing") {
            test("a suppressed write with no LibraryDataChanged broadcast offends") {
                val source =
                    """
                    suspend fun importAll() {
                        withContext(FirehoseSuppressed) { persistAll() }
                    }
                    """.trimIndent()
                ControlChannelDetector.firehoseSuppressedPairingViolation(source).shouldBeTrue()
            }

            test("a suppressed write that broadcasts the accelerator is clean") {
                val source =
                    """
                    suspend fun importAll() {
                        withContext(FirehoseSuppressed) { persistAll() }
                        bus.broadcastControl(SyncControl.LibraryDataChanged)
                    }
                    """.trimIndent()
                ControlChannelDetector.firehoseSuppressedPairingViolation(source).shouldBeFalse()
            }

            test("a file that merely reads the suppression gate is not implicated") {
                // The substrate reads currentCoroutineContext()[FirehoseSuppressed.Key]; it never enters
                // withContext(FirehoseSuppressed and owns no broadcast — must not be a false positive.
                val source =
                    "val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null"
                ControlChannelDetector.firehoseSuppressedPairingViolation(source).shouldBeFalse()
            }
        }

        context("sub-rule 3 — no resurrected lossy nudge") {
            test("a MutableSharedFlow<Unit> in data/repository offends") {
                ControlChannelDetector
                    .isBannedRefreshSignal(
                        path = "sharedLogic/src/commonMain/.../client/data/repository/FooRepositoryImpl.kt",
                        source = "private val refresh = MutableSharedFlow<Unit>()",
                    ).shouldBeTrue()
            }

            test("a MutableSharedFlow<Unit> in presentation offends") {
                ControlChannelDetector
                    .isBannedRefreshSignal(
                        path = "sharedLogic/src/commonMain/.../client/presentation/FooViewModel.kt",
                        source = "private val refresh = MutableSharedFlow<Unit>()",
                    ).shouldBeTrue()
            }

            test("a MutableSharedFlow<Unit> in data/sync (the sanctioned home) is clean") {
                ControlChannelDetector
                    .isBannedRefreshSignal(
                        path = "sharedLogic/src/commonMain/.../client/data/sync/PresenceRefreshSignal.kt",
                        source = "private val signal = MutableSharedFlow<Unit>()",
                    ).shouldBeFalse()
            }
        }

        context("sub-rule 1 (reverse) — no rotted allowlist entries") {
            test("an allowlist entry whose file emits nothing is reported as rotted") {
                val offenders =
                    ControlChannelDetector.unusedAllowlistEntries(
                        sourcesByFileName = mapOf("Ghost.kt" to "fun x() { doNothing() }"),
                        allowlist = mapOf("Ghost.kt" to setOf("ActivityChanged")),
                    )
                offenders.size shouldBe 1
                offenders.first() shouldContain "rotted"
            }

            test("an allowlist entry whose file is absent from sources is reported as rotted") {
                val offenders =
                    ControlChannelDetector.unusedAllowlistEntries(
                        sourcesByFileName = emptyMap(),
                        allowlist = mapOf("Ghost.kt" to setOf("ActivityChanged")),
                    )
                offenders.size shouldBe 1
            }

            test("an allowlist entry backed by a real emission is clean") {
                ControlChannelDetector
                    .unusedAllowlistEntries(
                        sourcesByFileName =
                            mapOf("Real.kt" to "fun x() { bus.broadcastControl(SyncControl.ServerInfoChanged) }"),
                        allowlist = mapOf("Real.kt" to setOf("ServerInfoChanged")),
                    ).shouldBeEmpty()
            }
        }
    })
