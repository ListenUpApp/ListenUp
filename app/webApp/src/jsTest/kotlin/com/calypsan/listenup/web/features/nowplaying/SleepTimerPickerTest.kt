package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement

/** A duration timer with [remainingMs] left of [totalMs]. */
private fun running(
    remainingMs: Long = MINUTES_LEFT * MS_PER_MINUTE,
    totalMs: Long = TIMER_MINUTES * MS_PER_MINUTE,
) = SleepTimerState.Active(
    mode = SleepTimerMode.Duration(minutes = TIMER_MINUTES),
    remainingMs = remainingMs,
    totalMs = totalMs,
    startedAt = 0L,
)

private const val MS_PER_MINUTE = 60_000L

/** The timer these specs arm, and how much of it is left once it has been running a while. */
private const val TIMER_MINUTES = 15

private const val MINUTES_LEFT = 14L

/** A rung further up the ladder than the one armed above, so the two cannot be confused. */
private const val A_LONGER_OPTION_MIN = 45

private const val AN_EXTENSION_MIN = 10

class SleepTimerPickerTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        /**
         * An open picker, with every parameter overridable.
         *
         * One shape for every case in this spec: a test that differs from its neighbour in one
         * argument says what it is about in that argument, rather than in seven lines of repeated
         * wiring.
         */
        fun picker(
            state: SleepTimerState = SleepTimerState.Inactive,
            hasChapters: Boolean = true,
            open: Boolean = true,
            onSet: (SleepTimerMode) -> Unit = {},
            onCancel: () -> Unit = {},
            onExtend: (Int) -> Unit = {},
            onDismiss: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                WebAppSurface {
                    SleepTimerPicker(
                        open = open,
                        state = state,
                        hasChapters = hasChapters,
                        onSet = onSet,
                        onCancel = onCancel,
                        onExtend = onExtend,
                        placement = PanelPlacement.Sheet,
                        onDismiss = onDismiss,
                    )
                }
            }

        test("a closed picker renders nothing at all") {
            val host = picker(open = false)

            host.querySelectorAll("dialog").length shouldBe 0
        }

        test("it offers the same durations the phone offers, worded the same way") {
            // The ladder is shared with SleepTimerSheet, and so is the wording: someone who set a
            // timer on their phone last night must not have to translate "120" here.
            val host = picker()

            host.querySelectorAll(".sleep-opt").length shouldBe 5
            val text = host.textContent.orEmpty()
            text shouldContain "15 min"
            text shouldContain "1 hour"
            text shouldContain "2 hours"

            host.closeDialogs()
        }

        test("end of chapter is offered when the book has chapters") {
            val host = picker(hasChapters = true)

            host.querySelectorAll(".sleep-eoc").length shouldBe 1

            host.closeDialogs()
        }

        test("end of chapter is withheld when the book has none") {
            // ⛔ It waits to be told a chapter turned over, and a book with no marks never will —
            // the timer would sit Active forever. A control that quietly does nothing is worse
            // than one that was never offered.
            val host = picker(hasChapters = false)

            host.querySelectorAll(".sleep-eoc").length shouldBe 0

            host.closeDialogs()
        }

        test("picking a duration reports that exact mode") {
            var picked: SleepTimerMode? = null
            val host = picker(onSet = { picked = it })

            host.buttonSaying("$A_LONGER_OPTION_MIN min")!!.click()
            awaitFrame()

            picked shouldBe SleepTimerMode.Duration(A_LONGER_OPTION_MIN)
            host.closeDialogs()
        }

        test("picking end of chapter reports that mode, not a duration") {
            var picked: SleepTimerMode? = null
            val host = picker(onSet = { picked = it })

            (host.querySelector(".sleep-eoc") as HTMLButtonElement).click()
            awaitFrame()

            picked shouldBe SleepTimerMode.EndOfChapter
            host.closeDialogs()
        }

        test("a running timer says how long is left instead of offering to start another") {
            val host = picker(state = running(remainingMs = 14 * MS_PER_MINUTE))

            host.querySelector(".sleep-left")?.textContent shouldBe "14:00"
            // The duration ladder is gone; what is offered now is more time, not a new timer.
            host.textContent.orEmpty() shouldNotContain "$A_LONGER_OPTION_MIN min"

            host.closeDialogs()
        }

        test("the countdown is a timer role that does not announce itself every second") {
            // A live region here would interrupt the book to read a new number 900 times over a
            // fifteen-minute timer. The role still lets someone ask for it.
            val host = picker(state = running())

            val countdown = host.querySelector(".sleep-left") as HTMLElement
            countdown.getAttribute("role") shouldBe "timer"
            countdown.getAttribute("aria-live") shouldBe "off"

            host.closeDialogs()
        }

        test("a running duration timer can be given more time") {
            var extended: Int? = null
            val host = picker(state = running(), onExtend = { extended = it })

            host.buttonSaying("+$AN_EXTENSION_MIN min")!!.click()
            awaitFrame()

            extended shouldBe AN_EXTENSION_MIN
            host.closeDialogs()
        }

        test("an end-of-chapter timer offers no extend, because it has no clock to add to") {
            val endOfChapter =
                SleepTimerState.Active(
                    mode = SleepTimerMode.EndOfChapter,
                    remainingMs = 0,
                    totalMs = 0,
                    startedAt = 0L,
                )
            val host = picker(state = endOfChapter)

            host.querySelectorAll(".sleep-opt").length shouldBe 0
            host.textContent.orEmpty() shouldContain "End of chapter"

            host.closeDialogs()
        }

        test("a running timer can be cancelled") {
            var cancelled = 0
            val host = picker(state = running(), onCancel = { cancelled++ })

            host.buttonSaying("Cancel timer")!!.click()
            awaitFrame()

            cancelled shouldBe 1
            host.closeDialogs()
        }

        test("a fade already under way offers no controls at all") {
            // The decision has been made and the book is ending. A button here would invite a tap
            // that arrives too late to mean anything.
            val host = picker(state = SleepTimerState.FadingOut)

            host.querySelectorAll(".sleep-opt").length shouldBe 0
            host.querySelectorAll(".sleep-eoc").length shouldBe 0
            host.buttonSaying("Cancel timer") shouldBe null

            host.closeDialogs()
        }

        test("it is a real modal dialog, not a div wearing the part") {
            val host = picker()

            val dialog = host.querySelector("dialog") as HTMLDialogElement
            dialog.isModal() shouldBe true

            host.closeDialogs()
        }

        test("closing reports the dismissal, so the caller's flag cannot drift") {
            var dismissed = 0
            val host = picker(onDismiss = { dismissed++ })

            (host.querySelector(".btn-secondary") as HTMLButtonElement).click()
            awaitFrame()

            dismissed shouldBe 1
            host.closeDialogs()
        }
    })

class SleepTimerBarTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun mount(content: @Composable () -> Unit): HTMLElement = mounts.mount { WebAppSurface { content() } }

        test("the sleep control is offered even for a book with no chapters") {
            // Unlike the chapters button: a duration timer needs nothing from the book.
            val host =
                mount {
                    TransportBar(
                        state = loadedPlayerState(durationMs = SEGMENT_MS, chapters = emptyList()),
                        progress = playerProgress(durationMs = SEGMENT_MS),
                        onPrimary = {},
                        onSeek = {},
                        onSkipBack = {},
                        onSkipForward = {},
                        onSetSpeed = {},
                    )
                }

            host.querySelectorAll("[aria-label='Sleep timer']").length shouldBe 1
        }

        test("an armed timer is visible on the bar, not only inside the dialog") {
            // ⛔ Finding out a timer was armed by having the book stop is the one way this feature
            // can feel broken rather than kind.
            val host =
                mount {
                    TransportBar(
                        state = loadedPlayerState(durationMs = SEGMENT_MS, sleepTimer = running()),
                        progress = playerProgress(durationMs = SEGMENT_MS),
                        onPrimary = {},
                        onSeek = {},
                        onSkipBack = {},
                        onSkipForward = {},
                        onSetSpeed = {},
                    )
                }

            // Armed, the countdown is the control (§3.9 gold), and its name says how long is left.
            val armed = host.querySelector(".tport-sleep") as HTMLElement
            armed.classList.contains("armed") shouldBe true
            armed.getAttribute("aria-label").orEmpty().startsWith("Sleep timer, ") shouldBe true
            armed.getAttribute("aria-label").orEmpty().endsWith(" left") shouldBe true
        }
    })

/** The book under the bar in these specs. */
private const val SEGMENT_MS = 9_000L
