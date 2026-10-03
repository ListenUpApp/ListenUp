package com.calypsan.listenup.web.features.readers

import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.SMALL_PHONE
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.zoomTextTo200
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private const val DAY_MS = 86_400_000L

/**
 * #1562 on web: a reader row says what pressing it does, and a name is never cut to an ellipsis with
 * no way to read the rest.
 */
class ReadersAccessibilityTest :
    FunSpec({
        val mounts = MountRegistry()
        val frames = ViewportFrames()
        afterTest {
            mounts.disposeAll()
            frames.disposeAll()
        }

        test("m-R2: every reader row is described as opening a profile") {
            val host =
                mounts.mount {
                    ReadersPanel(
                        state =
                            readersData(
                                reader(userId = "u1", displayName = "Ada Lovelace", finishes = listOf(READERS_NOW - DAY_MS)),
                                reader(userId = "u2", displayName = "Grace Hopper", progressPct = 40),
                            ),
                        nowMs = READERS_NOW,
                        onOpenProfile = {},
                        onSeeAll = {},
                    )
                }
            awaitFrame()

            val rows = host.querySelectorAll(".rdr-row").asList().filterIsInstance<HTMLElement>()
            rows.size shouldBe 2
            rows.forEach { row ->
                val description = document.getElementById(row.getAttribute("aria-describedby").shouldNotBeNull()).shouldNotBeNull()
                description.textContent shouldBe "View profile"
            }
        }

        test("a listen also logged on Hardcover reads as one phrase, with no stray ' on', and still says it opens a profile") {
            val host =
                mounts.mount {
                    ReadersPanel(
                        state =
                            readersData(
                                reader(
                                    userId = "u2",
                                    displayName = "Grace Hopper",
                                    finishes = listOf(READERS_NOW - DAY_MS),
                                    finishesAlsoOnHardcover = listOf(READERS_NOW - DAY_MS),
                                ),
                            ),
                        nowMs = READERS_NOW,
                        onOpenProfile = {},
                        onSeeAll = {},
                    )
                }
            awaitFrame()

            val row = host.querySelector(".rdr-row").shouldNotBeNull()
            row.querySelectorAll(".sr-only").asList().map { it.textContent } shouldBe listOf("Finished yesterday, also on Hardcover")
            document.getElementById(row.getAttribute("aria-describedby").shouldNotBeNull())!!.textContent shouldBe "View profile"
        }

        test("m-R3: at 320px and 200% text a name wraps to a second line rather than being cut, and says itself on hover") {
            val frame =
                frames.mount(SMALL_PHONE) {
                    InShell {
                        ReadersPanel(
                            state =
                                readersData(
                                    reader(
                                        userId = "u2",
                                        displayName = "Grace Hopper",
                                        hardcoverFinishes = listOf(READERS_NOW - DAY_MS),
                                        rating = readerRating("u2", 8),
                                    ),
                                ),
                            nowMs = READERS_NOW,
                            onOpenProfile = {},
                            onSeeAll = {},
                        )
                    }
                }
            frame.zoomTextTo200()

            val name = frame.find(".rdr-n")
            name.getAttribute("title") shouldBe "Grace Hopper"
            withClue("${name.scrollWidth}×${name.scrollHeight} in ${name.clientWidth}×${name.clientHeight}") {
                name.scrollWidth shouldBeLessThanOrEqual name.clientWidth
                name.scrollHeight shouldBeLessThanOrEqual name.clientHeight + 1
            }
        }
    })
