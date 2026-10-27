package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.NoCoverThemes
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.design.LocalCoverThemes
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.w3c.dom.DOMRect
import org.w3c.dom.Element
import org.w3c.dom.asList

/**
 * The phone title block (Simon, 2026-10-27): the title, the chapter and "Narrated by …", each centred on the cover to
 * within 1px. Measured on the words themselves (a DOM Range over their text), so a full-width box cannot pass for
 * centred words, and the title's chevron hangs outside them.
 */
class NowPlayingTitleCentringTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun phonePage(state: PlayerUiState.Loaded): ViewportFrame =
            frames.mount(width = PHONE, height = PHONE_HEIGHT) {
                CompositionLocalProvider(LocalCoverThemes provides NoCoverThemes) {
                    NowPlayingPage(state = state, progress = PlayerProgress.Zero, actions = NowPlayingActions())
                }
            }

        /** Each laid-out line of [element]'s words. */
        fun ViewportFrame.lineRects(element: Element): List<DOMRect> {
            val range = host.ownerDocument!!.createRange()
            range.selectNodeContents(element)
            return range.getClientRects().asList().filter { it.width > 0 }
        }

        fun DOMRect.centre(): Double = (left + right) / 2

        fun ViewportFrame.assertOnAxis(withTitleLines: Int) {
            val axis = rect(find(".np-cover")).centre()
            val title = find(".np-title a .np-title-t")
            val lines = lineRects(title)
            lines.size shouldBeGreaterThanOrEqual withTitleLines
            lines.forEachIndexed { index, line -> withClue("title line ${index + 1}") { line.centre() shouldBe (axis plusOrMinus 1.0) } }
            withClue("chapter") { lineRects(find(".np-mob.np-ch-title")).single().centre() shouldBe (axis plusOrMinus 1.0) }
            withClue("narrator line, mic included") {
                val parts = find(".np-mob.np-narrated").children.asList().map { rect(it) }
                ((parts.minOf { it.left } + parts.maxOf { it.right }) / 2) shouldBe (axis plusOrMinus 1.0)
            }
        }

        test("a short title, the chapter and the narrators sit on the cover's axis") {
            phonePage(wayOfKings(chapters = chapterRows(75, 1_235_000), currentChapterIndex = 5)).assertOnAxis(withTitleLines = 1)
        }

        test("a title that wraps to two lines keeps each line on the axis") {
            val long =
                wayOfKings(chapters = chapterRows(75, 1_235_000), currentChapterIndex = 5).let {
                    it.copy(book = it.book.copy(title = "The Way of Kings, Being the First Book of the Stormlight Archive"))
                }
            phonePage(long).assertOnAxis(withTitleLines = 2)
        }

        test("the chevron hangs outside the words, so they alone sit on the axis") {
            val frame = phonePage(wayOfKings(chapters = chapterRows(75, 1_235_000), currentChapterIndex = 5))
            val words = frame.lineRects(frame.find(".np-title a .np-title-t")).last()
            val link = frame.find(".np-title a")
            val chevron = frame.rect(link.lastElementChild ?: error("no chevron in ${link.outerHTML}"))
            (chevron.left >= words.right) shouldBe true
        }
    })

private const val PHONE_HEIGHT = 844
