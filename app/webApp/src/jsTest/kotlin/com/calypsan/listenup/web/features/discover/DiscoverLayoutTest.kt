package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import com.calypsan.listenup.web.ViewportFrames
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/** Data in every section, so each grid area has height. */
@Composable
private fun fullPage() {
    discoverPage(
        currentlyListening = listening(listenerOf("u1", live = true), listenerOf("u2", live = false)),
        books = DiscoverBooksUiState.Ready((1..6).map { pickBook("p$it", series = "Mistborn", seq = "$it") }),
        leaderboard = board(leaders()),
        activity = feed(*(1..9).map { act("a$it", "finished_book") }.toTypedArray()),
        shelves = shelvesOf(owner("u1", "Maya Chen", shelf("s1", "Cosy mysteries", 8))),
        recentlyAdded = RecentlyAddedUiState.Ready(listOf(recentBook("r1"), recentBook("r2"))),
    )
}

/**
 * One DOM in spec order, placed by width: at ≥ 1280px the Community aside sits beside the main
 * column; below that everything is one column in reading order (spec §3.7; plan D3).
 */
class DiscoverLayoutTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        test("at 1440 the Community aside sits beside the main column") {
            val frame = frames.mount(width = 1440, height = 2400) { fullPage() }
            val aside = frame.rect(frame.find(".disc-aside"))
            val listen = frame.rect(frame.find(".disc-a-listen"))
            aside.left shouldBeGreaterThan listen.right
            aside.top shouldBe (listen.top plusOrMinus 4.0)
        }

        test("at 1024 everything is one column, the leaderboard straight after Something new") {
            val frame = frames.mount(width = 1024, height = 2400) { fullPage() }
            val tops =
                listOf(".disc-a-listen", ".disc-a-new", ".disc-aside", ".disc-a-shelves", ".disc-a-recent")
                    .map { frame.rect(frame.find(it)).top }
            tops shouldBe tops.sorted()
            frame.rect(frame.find(".disc-aside")).left shouldBe (frame.rect(frame.find(".disc-a-new")).left plusOrMinus 1.0)
        }

        test("at 390 the aside's extra rows are hidden, and nothing scrolls sideways") {
            val frame = frames.mount(width = 390, height = 2400) { fullPage() }
            val extras = frame.findAll(".disc-aside-extra")
            (extras.size > 0) shouldBe true
            extras.all { !frame.isShown(it) } shouldBe true
            frame.horizontalOverflow() shouldBe 0
        }

        test("the page subtitle shows on the desktop and not on a phone") {
            val desktop = frames.mount(width = 1440) { fullPage() }
            desktop.isShown(desktop.find(".page-sub")) shouldBe true
            val phone = frames.mount(width = 390) { fullPage() }
            phone.isShown(phone.find(".page-sub")) shouldBe false
        }
    })
