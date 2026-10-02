package com.calypsan.listenup.web.features.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.api.dto.hardcover.HardcoverSyncProblem
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverBookToMatch
import com.calypsan.listenup.client.presentation.hardcover.HardcoverCandidateRow
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchEvent
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchedBook
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSyncStatus
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBook
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksEvent
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksUiState
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.ToastAction
import com.calypsan.listenup.web.design.ToastTone
import com.calypsan.listenup.web.mountAt
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableSharedFlow
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private val CONNECTED = HardcoverSettingsUiState.Connected(username = "simon", since = 0L, isDisconnecting = false)
private val REAL = HardcoverCandidateRow(427_578L, 9_001L, "Project Hail Mary", listOf("Andy Weir"), 2021, 8_107, true, true)
private val MATCH = HardcoverMatchedBook(427_578L, "Project Hail Mary", listOf("Andy Weir"), 2021, chosenByYou = true)

/** One toast a route asked the shell for. */
private data class ShownToast(
    val text: String,
    val tone: ToastTone,
    val action: ToastAction,
)

private fun HTMLElement.button(label: String): HTMLButtonElement =
    querySelectorAll("button").asList().map { it as HTMLButtonElement }.first { it.textContent.orEmpty().trim() == label }

/**
 * The three Hardcover surfaces wired into the shell: the toasts they raise, where each press leads,
 * and that a pick leaves Find on Hardcover for the book rather than stacking it in history.
 */
class HardcoverRoutesTest :
    FunSpec({
        var originalUrl = ""
        beforeTest { originalUrl = window.location.pathname + window.location.search }
        afterTest { window.history.replaceState(null, "", originalUrl) }

        test("a Sync now that failed is a toast with Try again, which syncs") {
            val toasts = mutableListOf<ShownToast>()
            var syncs = 0
            val (_, router, composition) =
                mountAt(
                    "/settings/hardcover",
                    openHardcover =
                        fixedHardcover(
                            CONNECTED.copy(sync = HardcoverSyncStatus.Problem(HardcoverSyncProblem.SYNC_NOW_FAILED)),
                            onSyncNow = { syncs++ },
                        ),
                    onActionToast = { text, tone, action -> toasts += ShownToast(text, tone, action) },
                )

            try {
                awaitFrame()
                toasts.map { it.text to it.tone } shouldBe
                    listOf("Couldn't reach Hardcover. Nothing was lost." to ToastTone.Failure)
                toasts.single().action.label shouldBe "Try again"
                toasts.single().action.onAction()
                syncs shouldBe 1
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("a stalled push raises no toast: its card says it") {
            val toasts = mutableListOf<String>()
            val (_, router, composition) =
                mountAt(
                    "/settings/hardcover",
                    openHardcover = fixedHardcover(CONNECTED.copy(sync = HardcoverSyncStatus.Problem(HardcoverSyncProblem.PUSH_STALLED))),
                    onActionToast = { text, _, _ -> toasts += text },
                )

            try {
                awaitFrame()
                toasts shouldBe emptyList()
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("choosing Only when I finish on the Hardcover page reaches the session") {
            val chosen = mutableListOf<HardcoverShareMode>()
            val (host, router, composition) =
                mountAt("/settings/hardcover", openHardcover = fixedHardcover(CONNECTED, onSetShareMode = { chosen += it }))

            try {
                host.button("Only when I finish").click()
                awaitFrame()
                chosen shouldBe listOf(HardcoverShareMode.FINISHED_ONLY)
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("Send and Not now on the earlier-books offer reach the session") {
            var sends = 0
            var dismissals = 0
            val (host, router, composition) =
                mountAt(
                    "/settings/hardcover",
                    openHardcover =
                        fixedHardcover(
                            CONNECTED.copy(history = HardcoverHistory.Offer(74)),
                            onSendHistory = { sends++ },
                            onDismissHistory = { dismissals++ },
                        ),
                )

            try {
                host.button("Send 74 books").click()
                host.button("Not now").click()
                awaitFrame()
                sends shouldBe 1
                dismissals shouldBe 1
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("Find on Hardcover from the Needs a match list opens that book's search") {
            val (host, router, composition) =
                mountAt(
                    "/settings/hardcover",
                    openHardcover =
                        fixedHardcover(
                            CONNECTED.copy(
                                booksToMatch = listOf(HardcoverBookToMatch("b7", "Piranesi", "Susanna Clarke", null, null)),
                                isMatchListKnown = true,
                            ),
                        ),
                )

            try {
                host.button("Find on Hardcover").click()
                awaitFrame()
                window.location.pathname shouldBe "/book/b7/hardcover"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("a pick says what it matched with Undo, and leaves for the book in place of the search") {
            val events = MutableSharedFlow<HardcoverMatchEvent>(extraBufferCapacity = 1)
            val toasts = mutableListOf<ShownToast>()
            var undos = 0
            val (_, router, composition) =
                mountAt(
                    "/book/42/hardcover",
                    openHardcoverMatch = fixedHardcoverMatch(HardcoverMatchUiState.BookMissing, events = events, onUndoLink = { undos++ }),
                    onActionToast = { text, tone, action -> toasts += ShownToast(text, tone, action) },
                )

            try {
                awaitFrame()
                events.emit(HardcoverMatchEvent.Linked(REAL, replaced = null))
                awaitFrame()

                toasts.map { it.text to it.tone } shouldBe
                    listOf("Matched to Project Hail Mary (Audiobook, 2021)" to ToastTone.Notice)
                toasts.single().action.label shouldBe "Undo"
                toasts.single().action.onAction()
                undos shouldBe 1
                window.location.pathname shouldBe "/book/42"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("a removed match leaves for the book too") {
            val events = MutableSharedFlow<HardcoverMatchEvent>(extraBufferCapacity = 1)
            val (_, router, composition) =
                mountAt(
                    "/book/42/hardcover",
                    openHardcoverMatch = fixedHardcoverMatch(HardcoverMatchUiState.BookMissing, events = events),
                )

            try {
                awaitFrame()
                events.emit(HardcoverMatchEvent.MatchRemoved)
                awaitFrame()
                window.location.pathname shouldBe "/book/42"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("Book Detail's panel changes the match on the find page, and removes it in place") {
            var removes = 0
            val (host, router, composition) =
                mountAt(
                    "/book/42",
                    openBookHardcover =
                        fixedBookHardcover(BookHardcoverUiState.Linked(MATCH, HardcoverBookSync.UP_TO_DATE), onRemoveMatch = { removes++ }),
                )

            try {
                awaitFrame()
                host.button("Remove match").click()
                removes shouldBe 1
                host.button("Change match").click()
                awaitFrame()
                window.location.pathname shouldBe "/book/42/hardcover"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("Book Detail's Sync with Hardcover reaches the book's session") {
            val synced = mutableListOf<Boolean>()
            val (host, router, composition) =
                mountAt(
                    "/book/42",
                    openBookHardcover = fixedBookHardcover(BookHardcoverUiState.KeptOff(), onSetSynced = { synced += it }),
                )

            try {
                awaitFrame()
                (host.querySelector(".hc-book-switch input[role=switch]") as HTMLElement).click()
                synced shouldBe listOf(true)
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("the kept-off row on the Hardcover page opens the kept-off page") {
            val (host, router, composition) =
                mountAt("/settings/hardcover", openHardcover = fixedHardcover(CONNECTED.copy(keptOffBookCount = 2)))

            try {
                awaitFrame()
                (host.querySelector(".hc-kept-row") as HTMLElement).click()
                awaitFrame()
                window.location.pathname shouldBe "/settings/hardcover/kept-off"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("the kept-off page lists the books, says each Sync again in a toast, and leaves after the last") {
            val toasts = mutableListOf<String>()
            val synced = mutableListOf<String>()
            val events = MutableSharedFlow<KeptOffBooksEvent>(extraBufferCapacity = 4)
            val (host, router, composition) =
                mountAt(
                    "/settings/hardcover/kept-off",
                    openHardcover =
                        fixedHardcover(
                            CONNECTED.copy(keptOffBookCount = 1),
                            keptOff = KeptOffBooksUiState.Loaded(listOf(KeptOffBook("b1", "Educated", "Tara Westover", null, null))),
                            keptOffEvents = events,
                            onSyncAgain = { synced += it },
                        ),
                    onToast = { toasts += it },
                )

            try {
                awaitFrame()
                host.querySelector("h1")!!.textContent shouldBe "Kept off Hardcover"
                host.button("Sync again").click()
                synced shouldBe listOf("b1")
                events.emit(KeptOffBooksEvent.SyncingAgain(title = "Educated", wasLast = true))
                awaitFrame()
                toasts shouldBe listOf("Syncing Educated with Hardcover again")
                window.location.pathname shouldBe "/settings/hardcover"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("a Sync again the server refused is a toast in the error's own words, and the page stays") {
            val toasts = mutableListOf<String>()
            val events = MutableSharedFlow<KeptOffBooksEvent>(extraBufferCapacity = 4)
            val error = TransportError.NetworkUnavailable()
            val (_, router, composition) =
                mountAt(
                    "/settings/hardcover/kept-off",
                    openHardcover =
                        fixedHardcover(
                            CONNECTED.copy(keptOffBookCount = 1),
                            keptOff = KeptOffBooksUiState.Loaded(listOf(KeptOffBook("b1", "Educated", "Tara Westover", null, null))),
                            keptOffEvents = events,
                        ),
                    onToast = { toasts += it },
                )

            try {
                awaitFrame()
                events.emit(KeptOffBooksEvent.ShowError(error))
                awaitFrame()
                toasts shouldBe listOf(error.message)
                window.location.pathname shouldBe "/settings/hardcover/kept-off"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("reached with nothing kept off, the kept-off page returns to the Hardcover page") {
            val (_, router, composition) =
                mountAt(
                    "/settings/hardcover/kept-off",
                    openHardcover = fixedHardcover(CONNECTED, keptOff = KeptOffBooksUiState.Loaded(emptyList())),
                )

            try {
                awaitFrame()
                awaitFrame()
                window.location.pathname shouldBe "/settings/hardcover"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })
