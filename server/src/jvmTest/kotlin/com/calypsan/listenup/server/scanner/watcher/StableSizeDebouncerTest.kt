package com.calypsan.listenup.server.scanner.watcher

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StableSizeDebouncerTest :
    FunSpec({

        // ---- Pure state-machine tests (no I/O, no coroutines) ----

        test("first tick captures state and returns Continue") {
            val tick = SettleWindow.initial().tick(size = 100, mtime = 1000, now = 0, settleMs = 2_000)
            val cont = tick.shouldBeInstanceOf<TickResult.Continue>()
            cont.next.lastSize shouldBe 100
            cont.next.lastMtime shouldBe 1000
            cont.next.stableSince shouldBe 0
        }

        test("Stable when size+mtime unchanged for the full settle window") {
            val s0 =
                SettleWindow
                    .initial()
                    .tick(100, 1000, now = 0, settleMs = 2_000)
                    .shouldBeInstanceOf<TickResult.Continue>()
                    .next
            val s1 =
                s0
                    .tick(100, 1000, now = 1_000, settleMs = 2_000)
                    .shouldBeInstanceOf<TickResult.Continue>()
                    .next
            s1.tick(100, 1000, now = 2_000, settleMs = 2_000) shouldBe TickResult.Stable
        }

        test("size change resets the settle window") {
            val s0 =
                SettleWindow
                    .initial()
                    .tick(100, 1000, now = 0, settleMs = 2_000)
                    .shouldBeInstanceOf<TickResult.Continue>()
                    .next
            val s1 =
                s0
                    .tick(100, 1000, now = 1_000, settleMs = 2_000)
                    .shouldBeInstanceOf<TickResult.Continue>()
                    .next

            // File grows mid-wait — stableSince resets to the new "now."
            val s2 =
                s1
                    .tick(200, 1500, now = 1_500, settleMs = 2_000)
                    .shouldBeInstanceOf<TickResult.Continue>()
                    .next
            s2.lastSize shouldBe 200
            s2.stableSince shouldBe 1_500

            // Need another full settle window from the change.
            s2.tick(200, 1500, now = 3_499, settleMs = 2_000).shouldBeInstanceOf<TickResult.Continue>()
            s2.tick(200, 1500, now = 3_500, settleMs = 2_000) shouldBe TickResult.Stable
        }

        test("mtime change resets even when size is the same") {
            val s0 =
                SettleWindow
                    .initial()
                    .tick(100, 1000, now = 0, settleMs = 2_000)
                    .shouldBeInstanceOf<TickResult.Continue>()
                    .next
            val s1 =
                s0
                    .tick(100, 2000, now = 1_500, settleMs = 2_000)
                    .shouldBeInstanceOf<TickResult.Continue>()
                    .next
            s1.lastMtime shouldBe 2000
            s1.stableSince shouldBe 1_500
        }

        test("settle boundary is inclusive at exactly settleMs") {
            val s0 =
                SettleWindow
                    .initial()
                    .tick(100, 1000, now = 0, settleMs = 2_000)
                    .shouldBeInstanceOf<TickResult.Continue>()
                    .next
            // Exactly at the boundary.
            s0.tick(100, 1000, now = 2_000, settleMs = 2_000) shouldBe TickResult.Stable
        }

        // ---- I/O-driven tests against the real filesystem ----
        // These use real wall-clock with short settle so they're fast (~250 ms).

        test("awaitStable returns true for a file that has been steady through the settle window") {
            runBlocking {
                val tmp = Files.createTempDirectory("listenup-stable-")
                val file = tmp / "track.mp3"
                file.writeBytes(byteArrayOf(1, 2, 3))
                try {
                    val debouncer =
                        StableSizeDebouncer(
                            settle = 200.milliseconds,
                            poll = 50.milliseconds,
                        )
                    debouncer.awaitStable(file) shouldBe true
                } finally {
                    Files.deleteIfExists(file)
                    Files.deleteIfExists(tmp)
                }
            }
        }

        test("awaitStable returns false when the file does not exist") {
            runBlocking {
                val tmp = Files.createTempDirectory("listenup-stable-")
                try {
                    val debouncer = StableSizeDebouncer(settle = 100.milliseconds, poll = 50.milliseconds)
                    debouncer.awaitStable(tmp / "missing.mp3") shouldBe false
                } finally {
                    Files.deleteIfExists(tmp)
                }
            }
        }

        test("awaitStable returns false when the file is deleted mid-wait") {
            runBlocking {
                val tmp = Files.createTempDirectory("listenup-stable-")
                val file = tmp / "track.mp3"
                file.writeBytes(byteArrayOf(1, 2, 3))
                try {
                    val debouncer =
                        StableSizeDebouncer(
                            settle = 5.seconds, // long enough that we can delete first
                            poll = 50.milliseconds,
                        )
                    val task =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            debouncer.awaitStable(file)
                        }
                    // Delete after at least one poll cycle so the debouncer notices the disappearance.
                    kotlinx.coroutines.delay(80)
                    Files.deleteIfExists(file)
                    task.await() shouldBe false
                } finally {
                    Files.deleteIfExists(tmp)
                }
            }
        }
    })
