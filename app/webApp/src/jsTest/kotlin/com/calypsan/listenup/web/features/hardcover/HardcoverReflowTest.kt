package com.calypsan.listenup.web.features.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverBookToMatch
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBook
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksUiState
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.SMALL_PHONE
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.clippedIn
import com.calypsan.listenup.web.contentOverflow
import com.calypsan.listenup.web.pastTheEdge
import com.calypsan.listenup.web.zoomTextTo200
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.dom.Div

private val LONG_TITLES =
    listOf(
        HardcoverBookToMatch("b1", "The Gate of the Feral Gods", "Matt Dinniman", null, null),
        HardcoverBookToMatch("b2", "Living from a Place of Surrender", "Michael A. Singer", null, null),
        HardcoverBookToMatch("b3", "Educated", "Tara Westover", null, null),
    )

private val CONNECTED =
    HardcoverSettingsUiState.Connected(
        username = "simonhull",
        since = 0L,
        isDisconnecting = false,
        lastSyncedAt = 1L,
        booksToMatch = LONG_TITLES,
        isMatchListKnown = true,
    )

/** A desktop window: two columns, room to spare at 100% — and none to spare at 200% text. */
private const val DESKTOP = 1280

/**
 * The narrowest a title may be squeezed and still read as words. The audit measured 7px at 200% text
 * and 18px at 320px — a letter or two a line.
 */
private const val READABLE_COLUMN_PX = 120.0

/**
 * #1562 on web: the Hardcover rows wrap instead of squeezing (WCAG 1.4.10 at 320px, 1.4.4 at 200%
 * text) — the button drops beneath its title rather than the title collapsing to a sliver, and nothing
 * pushes the page sideways.
 */
class HardcoverReflowTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun ViewportFrame.assertTitlesReadable(selector: String) {
            val titles = findAll(selector)
            titles.shouldNotBeEmpty()
            titles.forEach { title ->
                withClue("${title.textContent} is ${rect(title).width}px wide") {
                    rect(title).width shouldBeGreaterThanOrEqual READABLE_COLUMN_PX
                }
            }
        }

        fun ViewportFrame.assertNothingPastTheEdge() {
            withClue(pastTheEdge().joinToString("\n")) { contentOverflow() shouldBe 0 }
        }

        fun connected(
            width: Int,
            zoomed: Boolean,
        ): ViewportFrame =
            frames
                .mount(width, height = 900) {
                    InShell {
                        HardcoverPage(
                            state = CONNECTED.copy(keptOffBookCount = 2),
                            onConnect = {},
                            onDisconnect = {},
                            onSyncNow = {},
                            onSetShareMode = {},
                            onSendHistory = {},
                            onDismissHistory = {},
                            onFindMatch = {},
                            onOpenKeptOff = {},
                            onOpenSettings = {},
                            nowMs = 1L,
                        )
                    }
                }.also { if (zoomed) it.zoomTextTo200() }

        listOf(DESKTOP to true, SMALL_PHONE to true, SMALL_PHONE to false).forEach { (width, zoomed) ->
            val text = if (zoomed) "200% text" else "100% text"

            test("M-W4: at ${width}px and $text every Needs a match title reads as words, and nothing runs off") {
                val frame = connected(width, zoomed)

                frame.assertNothingPastTheEdge()
                frame.assertTitlesReadable(".hc-match-title")
                // The whole button is on screen, under its title when it will not fit beside it.
                frame.findAll(".hc-match-row .btn").forEach { button ->
                    frame.rect(button).right shouldBeLessThanOrEqual frame.rect(frame.find(".hc")).right
                }
            }

            test("M-W4: at ${width}px and $text no Hardcover panel clips its own content") {
                val frame = connected(width, zoomed)

                frame.clippedIn(".hc section").shouldBeEmpty()
            }

            test("M-W4: at ${width}px and $text the share options wrap their words rather than spill out of their boxes") {
                val frame = connected(width, zoomed)

                frame.findAll(".hc-share-mode .seg button").forEach { option ->
                    withClue(option.textContent) {
                        option.scrollHeight shouldBeLessThanOrEqual option.clientHeight
                        option.scrollWidth shouldBeLessThanOrEqual option.clientWidth
                    }
                }
            }

            test("M-K1: at ${width}px and $text the kept-off titles read as words, and nothing runs off") {
                val frame =
                    frames
                        .mount(width) {
                            InShell {
                                KeptOffBooksPage(
                                    state =
                                        KeptOffBooksUiState.Loaded(
                                            listOf(
                                                KeptOffBook("b1", "The Gate of the Feral Gods", "Matt Dinniman", null, null),
                                                KeptOffBook("b2", "Educated", "Tara Westover", null, null),
                                            ),
                                        ),
                                    onSyncAgain = {},
                                    onOpenSettings = {},
                                    onOpenHardcover = {},
                                )
                            }
                        }.also { if (zoomed) it.zoomTextTo200() }

                frame.assertNothingPastTheEdge()
                frame.assertTitlesReadable(".hc-match-title")
            }

            test("the Book Detail panel clips nothing at ${width}px and $text") {
                val frame =
                    frames
                        .mount(width) {
                            // In a column, as on Book Detail: a panel straight in the shell's flex column
                            // is squeezed to the frame's height, which no page does to it.
                            InShell {
                                Div {
                                    BookHardcoverPanel(
                                        state =
                                            BookHardcoverUiState.Linked(
                                                HardcoverMatchedBook(1L, "The Institute", listOf("Stephen King"), 2019, true, 2L),
                                                HardcoverBookSync.REMOVED_ON_HARDCOVER,
                                            ),
                                        onFindMatch = {},
                                        onRemoveMatch = {},
                                        onSetSynced = {},
                                    )
                                }
                            }
                        }.also { if (zoomed) it.zoomTextTo200() }

                frame.assertNothingPastTheEdge()
                frame.clippedIn("section").shouldBeEmpty()
            }
        }

        test("the breadcrumb wraps rather than pushing a phone's page sideways at 200% text") {
            val frame = connected(SMALL_PHONE, zoomed = true)

            val crumb = frame.find("nav.crumb")
            frame.rect(crumb).right shouldBeLessThanOrEqual frame.rect(frame.find(".hc")).right
        }
    })
