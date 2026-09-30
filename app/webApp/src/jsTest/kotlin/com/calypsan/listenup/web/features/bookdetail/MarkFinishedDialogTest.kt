package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.UtcOffset
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event
import kotlin.time.Instant

/**
 * "Mark as finished" asks for the days on web, as it always has on Android.
 *
 * Pinned in a fixed UTC−7 zone because the failure this guards is a timezone one: a day typed into
 * `<input type=date>` must become the start of that day where the reader is, not UTC midnight —
 * which, west of Greenwich, reads back as the day before.
 */
class MarkFinishedDialogTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        val pacific = FixedOffsetTimeZone(UtcOffset(hours = -7))
        // 2026-09-30T03:00Z is still the evening of the 29th in UTC−7.
        val nowMs = Instant.parse("2026-09-30T03:00:00Z").toEpochMilliseconds()
        val startedAtMs = Instant.parse("2026-09-02T18:00:00Z").toEpochMilliseconds()

        fun dialog(
            startedAt: Long? = startedAtMs,
            onConfirm: (Long, Long) -> Unit = { _, _ -> },
        ): HTMLElement =
            mounts.mount {
                WebAppSurface {
                    MarkFinishedDialog(
                        open = true,
                        startedAtMs = startedAt,
                        onConfirm = onConfirm,
                        onDismiss = {},
                        nowMs = nowMs,
                        timeZone = pacific,
                    )
                }
            }

        fun dateInputs(host: HTMLElement): List<HTMLInputElement> =
            host.querySelectorAll("input[type=date]").asList().filterIsInstance<HTMLInputElement>()

        fun confirm(host: HTMLElement): HTMLButtonElement =
            host
                .querySelectorAll(".dlg-actions button")
                .asList()
                .filterIsInstance<HTMLButtonElement>()
                .last()

        fun type(
            input: HTMLInputElement,
            value: String,
        ) {
            input.value = value
            input.dispatchEvent(Event("input", EventInit(bubbles = true)))
        }

        test("opens on Android's defaults: the recorded start day and today, in the reader's zone") {
            val (started, finished) = dateInputs(dialog())

            started.value shouldBe "2026-09-02"
            finished.value shouldBe "2026-09-29"
        }

        test("a book never started opens on today for both") {
            dateInputs(dialog(startedAt = null)).map { it.value } shouldBe listOf("2026-09-29", "2026-09-29")
        }

        test("neither picker offers a day after today") {
            dateInputs(dialog()).map { it.getAttribute("max") } shouldBe listOf("2026-09-29", "2026-09-29")
        }

        test("confirming untouched dates sends the recorded start and now, as Android does") {
            var sent: Pair<Long, Long>? = null
            val host = dialog(onConfirm = { start, finish -> sent = start to finish })

            confirm(host).click()

            sent shouldBe (startedAtMs to nowMs)
        }

        test("chosen days are sent as the start of those days in the reader's zone") {
            var sent: Pair<Long, Long>? = null
            val host = dialog(onConfirm = { start, finish -> sent = start to finish })
            val (started, finished) = dateInputs(host)

            type(started, "2019-03-01")
            type(finished, "2019-04-12")
            awaitFrame()
            confirm(host).click()

            sent shouldBe
                (
                    Instant.parse("2019-03-01T07:00:00Z").toEpochMilliseconds() to
                        Instant.parse("2019-04-12T07:00:00Z").toEpochMilliseconds()
                )
        }

        test("a finish before the start is refused, and the form says why") {
            var sent: Pair<Long, Long>? = null
            val host = dialog(onConfirm = { start, finish -> sent = start to finish })

            type(dateInputs(host)[1], "2026-09-01")
            awaitFrame()
            confirm(host).click()

            sent.shouldBeNull()
            confirm(host).disabled shouldBe true
            host.textContent.orEmpty() shouldContain "The finish date can’t be before the start date."
        }

        test("a typed day in the future is refused, whatever max says") {
            // `max` stops the picker; it does not stop a keyboard.
            val host = dialog()

            type(dateInputs(host)[1], "2026-10-04")
            awaitFrame()

            confirm(host).disabled shouldBe true
            host.textContent.orEmpty() shouldContain "These dates can’t be in the future."
        }

        test("a cleared day cannot be confirmed") {
            val host = dialog()

            type(dateInputs(host)[0], "")
            awaitFrame()

            confirm(host).disabled shouldBe true
        }
    })
