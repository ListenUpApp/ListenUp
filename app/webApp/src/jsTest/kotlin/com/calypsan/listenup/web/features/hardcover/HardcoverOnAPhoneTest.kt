package com.calypsan.listenup.web.features.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.client.presentation.hardcover.HardcoverBookToMatch
import com.calypsan.listenup.client.presentation.hardcover.HardcoverCandidateRow
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSearchState
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.MIN_TARGET_PX
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.Pointer
import com.calypsan.listenup.web.SMALL_PHONE
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.contentOverflow
import com.calypsan.listenup.web.pastTheEdge
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

private val BOOKS =
    listOf(
        HardcoverBookToMatch("b1", "The Left Hand of Darkness", "Ursula K. Le Guin", null, null),
        HardcoverBookToMatch("b2", "Piranesi", "Susanna Clarke", null, null),
    )

private val ROWS =
    listOf(
        HardcoverCandidateRow(1L, 9L, "Project Hail Mary", listOf("Andy Weir", "Ray Porter"), 2021, 47_312, true, true),
        HardcoverCandidateRow(2L, null, "Summary & Analysis of Project Hail Mary: A Guide", emptyList(), 2021, 3, false, false),
    )

/**
 * The Hardcover screens below the 760px line: one column, nothing past the edge, and every press
 * the canvas's 44px tall.
 */
class HardcoverOnAPhoneTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        listOf(SMALL_PHONE, PHONE).forEach { width ->
            test("at ${width}px the connected page fits, with Sync now and each Find on Hardcover on screen") {
                val frame =
                    frames.mount(width) {
                        InShell {
                            HardcoverPage(
                                state =
                                    HardcoverSettingsUiState.Connected(
                                        username = "simonhull",
                                        since = 0L,
                                        isDisconnecting = false,
                                        lastSyncedAt = 1L,
                                        booksToMatch = BOOKS,
                                        isMatchListKnown = true,
                                    ),
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
                    }

                withClue(frame.pastTheEdge().joinToString("\n")) { frame.contentOverflow() shouldBe 0 }
                val edge = frame.rect(frame.find(".hc")).right
                val presses = frame.findAll(".hc .btn")
                presses.shouldNotBeEmpty()
                presses.forEach {
                    frame.rect(it).right shouldBeLessThanOrEqual edge
                    frame.rect(it).height shouldBeGreaterThanOrEqual MIN_TARGET_PX
                }
            }

            test("at ${width}px on a touchscreen Update Hardcover fits, and each option takes a tap within 22px of its centre") {
                val frame =
                    frames.mount(width, pointer = Pointer.Touch) {
                        InShell {
                            HardcoverPage(
                                state =
                                    HardcoverSettingsUiState.Connected(
                                        username = "simonhull",
                                        since = 0L,
                                        isDisconnecting = false,
                                        shareMode = HardcoverShareMode.FINISHED_ONLY,
                                    ),
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
                    }

                withClue(frame.pastTheEdge().joinToString("\n")) { frame.contentOverflow() shouldBe 0 }
                val options = frame.findAll(".hc-share-mode .seg button")
                options.size shouldBe 2
                options.forEach { option ->
                    withClue(option.textContent) { frame.takesTapsWithin(option, MIN_TARGET_PX / 2 - 1) shouldBe true }
                }
            }

            test("at ${width}px the earlier-books offer fits, with Send above Not now, each the card's full width") {
                val frame =
                    frames.mount(width) {
                        InShell {
                            HardcoverPage(
                                state =
                                    HardcoverSettingsUiState.Connected(
                                        username = "simonhull",
                                        since = 0L,
                                        isDisconnecting = false,
                                        history = HardcoverHistory.Offer(74),
                                    ),
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
                    }

                withClue(frame.pastTheEdge().joinToString("\n")) { frame.contentOverflow() shouldBe 0 }
                val buttons = frame.findAll(".hc-history-acts .btn")
                buttons.size shouldBe 2
                val send = frame.rect(buttons[0])
                val notNow = frame.rect(buttons[1])
                buttons[0].textContent.orEmpty().trim() shouldBe "Send 74 books"
                (notNow.top >= send.bottom) shouldBe true
                send.width shouldBe notNow.width
                buttons.forEach { frame.rect(it).height shouldBeGreaterThanOrEqual MIN_TARGET_PX }
            }

            test("at ${width}px Find on Hardcover stacks each result, with Pick across its width") {
                val frame =
                    frames.mount(width) {
                        InShell {
                            HardcoverMatchPage(
                                state =
                                    HardcoverMatchUiState.Ready(
                                        "b1",
                                        "Project Hail Mary",
                                        "Andy Weir",
                                        null,
                                        null,
                                        "Project Hail Mary",
                                        HardcoverSearchState.Results(ROWS),
                                        null,
                                        null,
                                        false,
                                    ),
                                onQueryChange = {},
                                onSearch = {},
                                onSearchFor = {},
                                onPick = {},
                                onRemoveMatch = {},
                                onOpenLibrary = {},
                                onOpenBook = {},
                            )
                        }
                    }

                withClue(frame.pastTheEdge().joinToString("\n")) { frame.contentOverflow() shouldBe 0 }
                frame.findAll(".hc-result").forEach { row ->
                    val pick = frame.rect(row.querySelector(".btn")!!)
                    val title = frame.rect(row.querySelector(".hc-result-title")!!)
                    pick.top shouldBeGreaterThanOrEqual title.bottom
                    pick.height shouldBeGreaterThanOrEqual MIN_TARGET_PX
                }
            }
        }
    })
