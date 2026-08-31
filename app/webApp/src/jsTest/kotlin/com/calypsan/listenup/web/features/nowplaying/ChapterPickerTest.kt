package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebAppSurface
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { WebAppSurface { content() } }
    return host
}

/** An index no book in this spec has, to prove the rule holds well past the boundary too. */
private const val WAY_PAST_THE_END = 99

/** Ten minutes. Long enough that a formatted start time is unambiguous in an assertion. */
private const val CHAPTER_LENGTH_MS = 600_000L

internal fun chapterMarks(count: Int = 3): List<TransportChapter> =
    (0 until count).map {
        TransportChapter(title = "Chapter ${it + 1}", startMs = it * CHAPTER_LENGTH_MS)
    }

/**
 * An open picker over [chapterMarks], with every parameter overridable.
 *
 * One shape for every case in this spec: a test that differs from its neighbour in one argument
 * says what it is about in that argument, rather than in six lines of repeated wiring.
 */
private fun picker(
    chapters: List<TransportChapter> = chapterMarks(),
    currentIndex: Int? = 0,
    open: Boolean = true,
    onPick: (Int) -> Unit = {},
    onDismiss: () -> Unit = {},
): HTMLElement =
    mount {
        ChapterPicker(
            open = open,
            chapters = chapters,
            currentIndex = currentIndex,
            onPick = onPick,
            onDismiss = onDismiss,
        )
    }

class ChapterPickerTest :
    FunSpec({

        test("a closed picker renders nothing at all") {
            val host = picker(open = false)

            host.querySelectorAll("dialog").length shouldBe 0
        }

        test("an open picker lists every chapter with where it starts") {
            val host = picker()

            host.querySelectorAll(".chap-row").length shouldBe 3
            val text = host.textContent.orEmpty()
            text shouldContain "Chapter 2"
            // 600_000 ms in — the listener needs somewhere to land, not just a name.
            text shouldContain "10:00"

            host.closeDialogs()
        }

        test("picking a chapter reports its index") {
            var picked: Int? = null
            val host = picker(onPick = { picked = it })

            (host.querySelectorAll(".chap-row").item(2) as HTMLElement).click()

            picked shouldBe 2
            host.closeDialogs()
        }

        test("the chapter being listened to is marked, and says so to a screen reader") {
            // `aria-current` rather than a class alone: the highlight is information, and a class
            // says nothing to a reader who cannot see it.
            val host = picker(currentIndex = 1)

            val rows = host.querySelectorAll(".chap-row")
            (rows.item(0) as HTMLElement).getAttribute("aria-current") shouldBe null
            (rows.item(1) as HTMLElement).getAttribute("aria-current") shouldBe "true"
            host.querySelectorAll(".chap-row.on").length shouldBe 1

            host.closeDialogs()
        }

        test("no current chapter marks nothing rather than defaulting to the first") {
            // A book with no playhead in it yet must not claim the listener is in chapter one.
            val host = picker(currentIndex = null)

            host.querySelectorAll(".chap-row.on").length shouldBe 0

            host.closeDialogs()
        }

        test("it is a real modal dialog, not a div wearing the part") {
            // `showModal()` is what buys the focus trap, the inert page behind and Escape-to-close.
            // A `div` with role=dialog looks identical and provides none of it.
            val host = picker()

            val dialog = host.querySelector("dialog") as HTMLDialogElement
            dialog.isModal() shouldBe true

            host.closeDialogs()
        }

        test("closing reports the dismissal, so the caller's flag cannot drift") {
            var dismissed = 0
            val host = picker(onDismiss = { dismissed++ })

            (host.querySelector(".btn-ghost") as HTMLButtonElement).click()
            awaitFrame()

            dismissed shouldNotBe 0
            host.closeDialogs()
        }
    })

class ChapterSeekTest :
    FunSpec({

        test("a chapter resolves to where it starts") {
            chapterStartMs(chapterMarks(3), 2) shouldBe 2 * CHAPTER_LENGTH_MS
        }

        test("an index past the end resolves to nothing, not to the last chapter") {
            // ⛔ The rule this exists for. A stale index can only arrive from a caller reading a
            // list that has since changed, and clamping would land the listener at some OTHER
            // chapter with no way to tell they had been moved somewhere they did not choose.
            // Caught by a sabotage pass: the KDoc said this and nothing enforced it.
            chapterStartMs(chapterMarks(3), 3) shouldBe null
            chapterStartMs(chapterMarks(3), WAY_PAST_THE_END) shouldBe null
        }

        test("a negative index resolves to nothing, not to the first chapter") {
            chapterStartMs(chapterMarks(3), -1) shouldBe null
        }

        test("no chapters at all resolves to nothing") {
            chapterStartMs(emptyList(), 0) shouldBe null
        }
    })
