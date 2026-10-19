package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HomeCardRulesTest :
    FunSpec({
        fun book(downloaded: Boolean) =
            ContinueListeningBook(
                bookId = "babel",
                title = "Babel",
                authorNames = "R. F. Kuang",
                narratorNames = null,
                coverPath = null,
                coverHash = null,
                progress = 0.11f,
                currentPositionMs = 0L,
                totalDurationMs = 1L,
                lastPlayedAtMs = 0L,
                isDownloaded = downloaded,
            )

        test("online, every card plays") {
            book(downloaded = false).needsConnection(HomeConnectivity.Online) shouldBe false
        }

        test("offline or server unreachable, only a book that is not downloaded needs a connection") {
            HomeConnectivity.entries.filter { it != HomeConnectivity.Online }.forEach { connectivity ->
                book(downloaded = false).needsConnection(connectivity) shouldBe true
                book(downloaded = true).needsConnection(connectivity) shouldBe false
            }
        }

        test("counts group their thousands, as the strip writes them on every platform") {
            groupedCount(0) shouldBe "0"
            groupedCount(412) shouldBe "412"
            groupedCount(1_280) shouldBe "1,280"
            groupedCount(1_234_567) shouldBe "1,234,567"
        }
    })
