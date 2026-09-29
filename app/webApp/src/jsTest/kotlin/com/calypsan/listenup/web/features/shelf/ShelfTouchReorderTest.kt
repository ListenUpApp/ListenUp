package com.calypsan.listenup.web.features.shelf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.presentation.shelf.ShelfDetailUiState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.MIN_TARGET_PX
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.Pointer
import com.calypsan.listenup.web.SMALL_PHONE
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.contentOverflow
import com.calypsan.listenup.web.pastTheEdge
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.pointerevents.PointerEventInit

private fun shelfBook(
    id: String,
    title: String,
) = ShelfBook(id = BookId(id), title = title, authorNames = listOf("Susanna Clarke"), coverPath = null, coverHash = null)

private val DUNE = shelfBook("a", "Dune")
private val PIRANESI = shelfBook("b", "Piranesi")
private val EMMA = shelfBook("c", "Emma")

private fun owned(books: List<ShelfBook>) =
    ShelfDetailUiState.Ready(
        detail =
            ShelfDetail(
                id = ShelfId("s1"),
                name = "Comfort reads",
                description = null,
                isPrivate = false,
                isOwner = true,
                bookCount = books.size,
                totalDurationSeconds = 0,
                books = books,
            ),
        isOwner = true,
    )

private fun touch(
    type: String,
    y: Double,
): PointerEvent =
    PointerEvent(
        type,
        PointerEventInit(pointerId = 3, pointerType = "touch", clientY = y.toInt(), bubbles = true, cancelable = true),
    )

/**
 * Reordering a shelf without a mouse.
 *
 * HTML5 drag-and-drop is the desktop gesture, and a touchscreen does not reliably start one; the
 * grip's arrow keys cover a keyboard and nothing else. So each book carries visible Move earlier /
 * Move later buttons — the same two moves Android offers as accessibility actions — and the grip
 * itself drags under a finger with pointer events. Every move is said out loud in a live region,
 * because a list that silently rearranges itself under a screen reader has lost the reader's place.
 */
class ShelfTouchReorderTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        /** A shelf whose order really changes when a move is asked for, as the ViewModel's would. */
        fun shelf(
            width: Int = PHONE,
            pointer: Pointer = Pointer.Touch,
            sent: MutableList<List<String>> = mutableListOf(),
        ) = frames.mount(width, pointer = pointer) {
            var books by remember { mutableStateOf(listOf(DUNE, PIRANESI, EMMA)) }
            InShell {
                ShelfDetailPage(
                    state = owned(books),
                    notice = null,
                    onDismissNotice = {},
                    onOpenBook = {},
                    onRemoveBook = {},
                    onReorder = { ids ->
                        sent += ids
                        books = ids.map { id -> listOf(DUNE, PIRANESI, EMMA).first { it.idString == id } }
                    },
                    onEditShelf = {},
                    onOpenLibrary = {},
                )
            }
        }

        fun titles(frame: ViewportFrame) = frame.findAll(".shelf-book-t").map { it.textContent.orEmpty() }

        test("every book says how to move it, by name") {
            val frame = shelf()

            frame.findAll(".shelf-move").map { it.getAttribute("aria-label") } shouldBe
                listOf(
                    "Move Dune earlier",
                    "Move Dune later",
                    "Move Piranesi earlier",
                    "Move Piranesi later",
                    "Move Emma earlier",
                    "Move Emma later",
                )
        }

        test("a move that would fall off either end is not offered") {
            val frame = shelf()
            val moves = frame.findAll(".shelf-move")

            moves.first().hasAttribute("disabled") shouldBe true
            moves.last().hasAttribute("disabled") shouldBe true
            moves.drop(1).dropLast(1).none { it.hasAttribute("disabled") } shouldBe true
        }

        test("Move later swaps a book with the next one") {
            val sent = mutableListOf<List<String>>()
            val frame = shelf(sent = sent)

            frame.find("[aria-label='Move Dune later']").click()
            awaitFrame()

            sent shouldBe listOf(listOf("b", "a", "c"))
            titles(frame) shouldBe listOf("Piranesi", "Dune", "Emma")
        }

        test("a move is announced, with where the book landed") {
            val frame = shelf()

            frame.find("[aria-label='Move Emma earlier']").click()
            awaitFrame()

            val live = frame.find(".shelf .sr-only[role=status]")
            live.getAttribute("aria-live") shouldBe "polite"
            live.textContent shouldBe "Emma moved to position 2 of 3."
        }

        test("focus stays on the book that moved, so pressing again keeps moving it") {
            val frame = shelf()

            frame.find("[aria-label='Move Dune later']").let {
                it.focus()
                it.click()
            }
            awaitFrame()
            awaitFrame()

            frame
                .find(".shelf-books")
                .ownerDocument!!
                .activeElement
                ?.getAttribute("aria-label") shouldBe
                "Move Dune later"
        }

        test("a book moved to the end hands focus to the move it still has") {
            val frame = shelf()

            frame.find("[aria-label='Move Piranesi later']").let {
                it.focus()
                it.click()
            }
            awaitFrame()
            awaitFrame()

            val active = frame.find(".shelf-books").ownerDocument!!.activeElement
            withClue("active: ${active?.tagName} ${active?.getAttribute("aria-label")}") {
                active?.getAttribute("aria-label") shouldBe "Move Piranesi earlier"
            }
        }

        listOf(SMALL_PHONE, PHONE).forEach { width ->
            test("at ${width}px every reorder and remove control is a thumb-sized target, and the row fits") {
                val frame = shelf(width = width)

                frame.findAll(".shelf-move, .shelf-grip, .shelf-book-x").forEach { control ->
                    withClue(control.getAttribute("aria-label")) {
                        frame.takesTapsWithin(control, MIN_TARGET_PX / 2 - 1) shouldBe true
                    }
                }
                frame.findAll(".shelf-move").forEach { move ->
                    frame.rect(move).width shouldBeGreaterThanOrEqual MIN_TARGET_PX
                    frame.rect(move).height shouldBeGreaterThanOrEqual MIN_TARGET_PX
                }
                withClue(frame.pastTheEdge()) { frame.contentOverflow() shouldBe 0 }
            }
        }

        // ---- the grip, under a finger

        fun rowCentre(
            frame: ViewportFrame,
            index: Int,
        ): Double = frame.rect(frame.findAll(".shelf-book")[index]).let { it.top + it.height / 2 }

        test("the grip does not hand a finger's drag to the page as a scroll") {
            val frame = shelf()

            frame.css(frame.find(".shelf-grip"), "touch-action") shouldBe "none"
        }

        test("dragging the grip with a finger drops the book where the finger lifts") {
            val sent = mutableListOf<List<String>>()
            val frame = shelf(sent = sent)
            val grip = frame.findAll(".shelf-grip").first()

            grip.dispatchEvent(touch("pointerdown", rowCentre(frame, 0)))
            grip.dispatchEvent(touch("pointermove", rowCentre(frame, 1)))
            grip.dispatchEvent(touch("pointermove", rowCentre(frame, 2)))
            awaitFrame()
            frame.findAll(".shelf-book")[2].classList.contains("is-target") shouldBe true
            grip.dispatchEvent(touch("pointerup", rowCentre(frame, 2)))
            awaitFrame()

            sent shouldBe listOf(listOf("b", "c", "a"))
            frame.findAll(".shelf-book.is-target").size shouldBe 0
        }

        test("a finger drag the browser takes back moves nothing") {
            // `pointercancel` is the browser reclaiming the gesture (a scroll, a system swipe), and
            // `lostpointercapture` is capture ending without a release. Neither is a drop.
            listOf("pointercancel", "lostpointercapture").forEach { ending ->
                val sent = mutableListOf<List<String>>()
                val frame = shelf(sent = sent)
                val grip = frame.findAll(".shelf-grip").first()

                grip.dispatchEvent(touch("pointerdown", rowCentre(frame, 0)))
                grip.dispatchEvent(touch("pointermove", rowCentre(frame, 2)))
                grip.dispatchEvent(touch(ending, rowCentre(frame, 2)))
                grip.dispatchEvent(touch("pointerup", rowCentre(frame, 2)))
                awaitFrame()

                sent shouldBe emptyList()
                frame.findAll(".shelf-book.is-target").size shouldBe 0
                frames.disposeAll()
            }
        }

        test("a mouse on the grip is left to the browser's own drag") {
            val sent = mutableListOf<List<String>>()
            val frame = shelf(pointer = Pointer.Mouse, sent = sent)
            val grip = frame.findAll(".shelf-grip").first()
            val mouse = { type: String, y: Double ->
                PointerEvent(
                    type,
                    PointerEventInit(pointerId = 1, pointerType = "mouse", clientY = y.toInt(), bubbles = true),
                )
            }

            grip.dispatchEvent(mouse("pointerdown", rowCentre(frame, 0)))
            grip.dispatchEvent(mouse("pointermove", rowCentre(frame, 2)))
            grip.dispatchEvent(mouse("pointerup", rowCentre(frame, 2)))
            awaitFrame()

            sent shouldBe emptyList()
        }
    })
