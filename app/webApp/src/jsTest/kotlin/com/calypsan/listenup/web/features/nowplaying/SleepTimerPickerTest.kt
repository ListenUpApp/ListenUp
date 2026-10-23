package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.PlayerChapter
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event
import kotlinx.browser.window

/** A duration timer with [remainingMs] left of [totalMs]. */
private fun running(
    remainingMs: Long = MINUTES_LEFT * MS_PER_MINUTE,
    totalMs: Long = TIMER_MINUTES * MS_PER_MINUTE,
    minutes: Int = TIMER_MINUTES,
) = SleepTimerState.Active(
    mode = SleepTimerMode.Duration(minutes = minutes),
    remainingMs = remainingMs,
    totalMs = totalMs,
    startedAt = 0L,
)

private const val MS_PER_MINUTE = 60_000L

private fun HTMLElement.backgroundColor(): String = window.getComputedStyle(this).backgroundColor

/** What [token] resolves to here, in whichever theme the page is in: a probe painted with it. */
private fun HTMLElement.tokenColour(token: String): String {
    val probe = ownerDocument!!.createElement("div") as HTMLElement
    probe.style.setProperty("background-color", "var($token)")
    appendChild(probe)
    return probe.backgroundColor().also { probe.remove() }
}

/** Compose HTML listens for bubbling events; a default-constructed `Event` does not bubble. */
private fun bubbling(): dynamic = js("({ bubbles: true })")

/** The timer these specs arm, and how much of it is left once it has been running a while. */
private const val TIMER_MINUTES = 15

private const val MINUTES_LEFT = 14L

/** A rung further up the ladder than the one armed above, so the two cannot be confused. */
private const val A_LONGER_OPTION_MIN = 45

private const val AN_EXTENSION_MIN = 10

/** Chapter 6 of 75, "Bridge Four", 20m 35s long: the board's chapter. */
private val BRIDGE_FOUR =
    PlayerChapter(
        index = 5,
        number = 6,
        total = 75,
        title = "Bridge Four",
        isGenericTitle = false,
        isWholeBook = false,
        startMs = 0,
        durationMs = 1_235_000,
    )

private val WHOLE_BOOK = PlayerChapter(0, 1, 1, "", isGenericTitle = true, isWholeBook = true, startMs = 0, durationMs = 9_000_000)

/** Eight minutes of the chapter left, in content time. */
private const val EIGHT_MINUTES = 480_000L

class SleepTimerPickerTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        /**
         * An open picker, with every parameter overridable.
         *
         * One shape for every case in this spec: a test that differs from its neighbour in one argument says what it
         * is about in that argument, rather than in a dozen lines of repeated wiring.
         */
        fun picker(
            state: SleepTimerState = SleepTimerState.Inactive,
            chapter: PlayerChapter = BRIDGE_FOUR,
            countdownMs: Long? = (state as? SleepTimerState.Active)?.remainingMs,
            meterFraction: Float = 0f,
            open: Boolean = true,
            onSet: (SleepTimerMode) -> Unit = {},
            onCancel: () -> Unit = {},
            onExtend: (Int) -> Unit = {},
            onKeepListening: () -> Unit = {},
            onDismiss: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                WebAppSurface {
                    SleepTimerPicker(
                        open = open,
                        state = state,
                        chapter = chapter,
                        chapterRemainingMs = EIGHT_MINUTES,
                        countdownMs = countdownMs,
                        meterFraction = meterFraction,
                        nowMs = 1_761_000_000_000,
                        placement = PanelPlacement.Sheet,
                        onSet = onSet,
                        onCancel = onCancel,
                        onExtend = onExtend,
                        onKeepListening = onKeepListening,
                        onDismiss = onDismiss,
                    )
                }
            }

        /** The panel's own buttons, after its header's Close. */
        fun HTMLElement.contentButtons(): List<HTMLElement> =
            querySelectorAll("dialog button").asList().map { it as HTMLElement }.filter { it.closest(".dlg-head") == null }

        test("a closed picker renders nothing at all") {
            val host = picker(open = false)

            host.querySelectorAll("dialog").length shouldBe 0
        }

        test("it offers the same durations the phone offers, worded the same way") {
            // The ladder is the shared SleepTimerChoices, and so is the wording: someone who set a timer on their phone
            // last night must not have to translate "120" here.
            val host = picker()

            host.querySelectorAll("[role=group][aria-label='Durations'] .sleep-opt").length shouldBe 5
            val text = host.textContent.orEmpty()
            text shouldContain "15 min"
            text shouldContain "1 hour"
            text shouldContain "2 hours"

            host.closeDialogs()
        }

        test("End of chapter comes first, gold, with the chapter and the time") {
            val host = picker()

            val first = host.contentButtons().first()
            first.classList.contains("sleep-eoc") shouldBe true
            first.textContent.orEmpty() shouldContain "Pauses after Bridge Four · in 8m"
            first.backgroundColor() shouldBe (first.parentElement as HTMLElement).tokenColour("--sleep-container")

            host.closeDialogs()
        }

        test("a whole-book offers no End of chapter") {
            // ⛔ It waits to be told a chapter turned over, and a book with no marks never will — the timer would sit
            // Active forever. A control that quietly does nothing is worse than one that was never offered.
            val host = picker(chapter = WHOLE_BOOK)

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

        test("the custom field accepts 1–720 and Start reports it") {
            val picked = mutableListOf<SleepTimerMode>()
            val host = picker(onSet = { picked += it })
            val field = host.querySelector("input[type=number]") as HTMLInputElement
            field.getAttribute("min") shouldBe "1"
            field.getAttribute("max") shouldBe "720"
            field.labels.length shouldBe 1

            suspend fun type(text: String) {
                field.value = text
                field.dispatchEvent(Event("input", bubbling()))
                awaitFrame()
            }

            type("37")
            host.buttonSaying("Start")!!.click()
            type("0")
            host.buttonSaying("Start")!!.click()
            picked shouldBe listOf(SleepTimerMode.Duration(37), SleepTimerMode.Duration(1))

            type("ten")
            host.buttonSaying("Start")!!.disabled shouldBe true

            host.closeDialogs()
        }

        test("running: countdown, Pauses at, the meter, extend, cancel") {
            var cancelled = 0
            var dismissed = 0
            val host =
                picker(
                    state = running(remainingMs = 1_421_000, totalMs = 1_800_000, minutes = 30),
                    meterFraction = 1_421_000f / 1_800_000f,
                    onCancel = { cancelled++ },
                    onDismiss = { dismissed++ },
                )

            val timer = host.querySelector("[role=timer][aria-live=off]") as HTMLElement
            timer.textContent shouldBe "23:41"
            host.querySelector(".sleep-pauses")!!.textContent.orEmpty() shouldMatch
                Regex("Pauses at \\d{1,2}:\\d{2}( ?[AP]M)? · 30 min timer")
            val meter = host.querySelector("[role=img][aria-label='23:41 of 30:00 remaining']") as HTMLElement
            val fill = meter.firstElementChild!!.getBoundingClientRect().width / meter.getBoundingClientRect().width
            fill shouldBe (0.79 plusOrMinus 0.01)
            host.querySelectorAll("[role=group][aria-label='Add time'] button").length shouldBe 3

            host.buttonSaying("Cancel timer")!!.click()
            awaitFrame()
            cancelled shouldBe 1
            dismissed shouldBe 0

            host.closeDialogs()
        }

        test("a running timer says how long is left instead of offering to start another") {
            val host = picker(state = running(remainingMs = 14 * MS_PER_MINUTE))

            host.querySelector(".sleep-left")?.textContent shouldBe "14:00"
            // The duration ladder is gone; what is offered now is more time, not a new timer.
            host.textContent.orEmpty() shouldNotContain "$A_LONGER_OPTION_MIN min"

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

        test("end-of-chapter running names the chapter and offers no extend") {
            val endOfChapter =
                SleepTimerState.Active(mode = SleepTimerMode.EndOfChapter, remainingMs = 0, totalMs = 0, startedAt = 0L)
            val host = picker(state = endOfChapter, countdownMs = EIGHT_MINUTES, meterFraction = 0.4f)

            host.querySelector(".sleep-left")?.textContent shouldBe "8:00"
            host.querySelector(".sleep-pauses")?.textContent shouldBe "Until the end of Bridge Four · 8m"
            host.querySelectorAll(".sleep-opt").length shouldBe 0
            host.buttonSaying("Cancel timer").shouldNotBeNull()

            host.closeDialogs()
        }

        test("fading: the gold card's words and Keep listening") {
            // Changed on purpose (PR 4): this used to assert that a fade offers no controls at all. The spec now asks
            // for Keep listening (§5.6 "Sleep fading", PT7), which cancels the timer and puts full volume back.
            var kept = 0
            val host = picker(state = SleepTimerState.FadingOut, onKeepListening = { kept++ })

            val card = host.querySelector(".np-card.sleep") as HTMLElement
            card.getAttribute("role") shouldBe "status"
            card.textContent.orEmpty() shouldContain "Fading out…"
            card.textContent.orEmpty() shouldContain "Your sleep timer is ending. Playback pauses in a moment."
            host.querySelectorAll(".sleep-opt").length shouldBe 0
            host.querySelectorAll(".sleep-eoc").length shouldBe 0
            host.buttonSaying("Cancel timer") shouldBe null

            host.buttonSaying("Keep listening")!!.click()
            awaitFrame()
            kept shouldBe 1

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

        test("an armed end-of-chapter timer reads its real countdown on the bar, not 0:00") {
            val endOfChapter = SleepTimerState.Active(SleepTimerMode.EndOfChapter, 0, 0, 0)
            val host =
                mount {
                    TransportBar(
                        state =
                            loadedPlayerState(
                                durationMs = 3_600_000,
                                chapters = chapterRows(3, 1_200_000),
                                currentChapterIndex = 1,
                                sleepTimer = endOfChapter,
                            ),
                        progress = PlayerProgress(720_000, 1_200_000, 1_920_000, 3_600_000),
                        onPrimary = {},
                        onSeek = {},
                        onSkipBack = {},
                        onSkipForward = {},
                        onSetSpeed = {},
                    )
                }

            val armed = host.querySelector(".tport-sleep") as HTMLElement
            armed.textContent shouldBe "8:00"
            armed.getAttribute("aria-label") shouldBe "Sleep timer, 8:00 left"
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
