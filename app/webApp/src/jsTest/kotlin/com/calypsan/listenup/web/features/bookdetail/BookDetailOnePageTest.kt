package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.presentation.bookdetail.ReadingStage
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitPresent
import com.calypsan.listenup.web.features.readers.reader
import com.calypsan.listenup.web.features.readers.readersData
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Book Detail is one page whose sections follow the visit's stage order (spec §2.4): one column on a phone, two on a
 * desktop (§3.2.4), a section never changing column; the social line sits under the actions.
 */
class BookDetailOnePageTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        fun page(
            width: Int,
            stage: ReadingStage,
        ) = frames.mount(width, height = 2400) {
            BookDetailPage(
                state =
                    readyBook(
                        readingStage = stage,
                        series = listOf(BookSeries(seriesId = "s1", seriesName = "The Stormlight Archive", sequence = 1.0)),
                    ),
                onOpenLibrary = {},
                onPlay = {},
                onPlayFromChapter = {},
                readers = readersData(reader(userId = "u1", displayName = "Maya", progressPct = 40)),
                onRetryConnection = {},
            )
        }

        test("in progress on a phone: one column, Chapters before About, and no tabs") {
            val frame = page(PHONE, ReadingStage.InProgress)
            awaitPresent(frame.host, ".bd-flow")
            frame.findAll(".bd-flow").size shouldBe 1
            frame.findAll(".bd-cols").size shouldBe 0
            frame.findAll("[role=tab]").size shouldBe 0
            frame.rect(frame.find("#bd-chapters")).top shouldBeLessThan frame.rect(frame.find(".bd-desc")).top
        }

        test("not started on a phone leads with About") {
            val frame = page(PHONE, ReadingStage.NotStarted)
            awaitPresent(frame.host, ".bd-flow")
            frame.rect(frame.find(".bd-desc")).top shouldBeLessThan frame.rect(frame.find("#bd-chapters")).top
        }

        test("a desktop splits the order into two columns: Chapters in the main, Readers in the side") {
            val frame = page(DESKTOP, ReadingStage.InProgress)
            awaitPresent(frame.host, ".bd-cols")
            frame.findAll(".bd-cols").size shouldBe 1
            frame.find(".bd-main").querySelector("#bd-chapters") shouldNotBe null
            frame.find(".bd-side").querySelector("#readers") shouldNotBe null
        }

        test("the social line sits under the actions and links to Readers") {
            val frame = page(PHONE, ReadingStage.InProgress)
            awaitPresent(frame.host, ".bd-flow")
            val social = frame.find(".bd-head a.bd-social")
            frame.rect(frame.find(".bd-actions")).bottom shouldBeLessThan frame.rect(social).bottom
            social.getAttribute("href") shouldBe "#readers"
        }

        test("a phone clamps the synopsis to two lines while listening; a desktop shows it whole") {
            val phone = page(PHONE, ReadingStage.InProgress)
            awaitPresent(phone.host, ".bd-flow")
            phone.find(".bd-desc").classList.contains("is-clamped") shouldBe true
            phone.find(".bd-desc").style.getPropertyValue("--clamp") shouldBe "2"
            frames.disposeAll()
            val desktop = page(DESKTOP, ReadingStage.InProgress)
            awaitPresent(desktop.host, ".bd-cols")
            desktop.find(".bd-desc").classList.contains("is-clamped") shouldBe false
        }
    })

private const val DESKTOP = 1280
