package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.SMALL_PHONE
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

/** A reader's default font at 1.3× — what the browser's own text-size setting changes. */
private const val LARGE_TEXT_ROOT = "20.8px"

/** Spec §2.4's slot primary height. */
private const val RESUME_HEIGHT = 52.0

/** Rounding slack for comparing two laid-out edges. */
private const val EDGE_SLACK = 0.5

/** Home on a phone, measured in the real shell with the real sheet (see [ViewportFrames]). */
class HomePhoneGeometryTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun home(width: Int): ViewportFrame =
            frames.mount(width) {
                InShell {
                    HomePage(readyHome(), weekStats(), HomeActions.None, isWide = false, selection = null)
                }
            }

        fun ViewportFrame.largeText() {
            host.ownerDocument!!
                .documentElement!!
                .asDynamic()
                .style.fontSize = LARGE_TEXT_ROOT
        }

        test("the slot's primary is a 52px pill (§2.4)") {
            val frame =
                frames.mount(PHONE) {
                    InShell {
                        HomePage(
                            readyHome(slot = finishedSlot()),
                            weekStats(),
                            HomeActions.None,
                            isWide = false,
                            selection = null,
                        )
                    }
                }
            val resume = frame.find(".home-hero-resume")
            val height = frame.rect(resume).height
            val radius = frame.css(resume, "border-top-left-radius").removeSuffix("px").toDouble()

            height shouldBe (RESUME_HEIGHT plusOrMinus EDGE_SLACK)
            withClue("a ${radius}px corner on a ${height}px button") { radius shouldBeGreaterThanOrEqual height / 2 }
        }

        listOf(SMALL_PHONE, PHONE).forEach { width ->
            test("at ${width}px and 1.3× text, Today sits whole under the last bar, clear of the day before") {
                val frame = home(width).apply { largeText() }
                val card = frame.find(".home-stats-card")
                // The card's padding box: Today may overhang the bar row into the padding, never the border.
                val inside = frame.rect(card).right - frame.css(card, "border-right-width").removeSuffix("px").toDouble()
                val lastBar = frame.rect(frame.findAll(".home-bar").last())
                val labels = frame.findAll(".home-bar-label").map { frame.rect(it) }
                val today = labels.last()
                val dayBefore = labels[labels.size - 2]

                frame.findAll(".home-bar-label").last().textContent shouldBe "Today"
                withClue("Today ${today.left}..${today.right} inside a card ending at $inside") {
                    today.right shouldBeLessThanOrEqual inside + EDGE_SLACK
                }
                withClue("Today ${today.left}..${today.right} centred under its bar ${lastBar.left}..${lastBar.right}") {
                    val todayCentre = (today.left + today.right) / 2
                    todayCentre shouldBe ((lastBar.left + lastBar.right) / 2 plusOrMinus EDGE_SLACK)
                }
                withClue("Today starts at ${today.left}; the day before ends at ${dayBefore.right}") {
                    dayBefore.right shouldBeLessThanOrEqual today.left
                }
                withClue("every bar keeps one width, Today's included") {
                    frame
                        .findAll(".home-bar")
                        .map { frame.rect(it).width }
                        .distinct()
                        .size shouldBe 1
                }
            }
        }
    })
