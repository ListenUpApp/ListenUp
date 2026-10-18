package com.calypsan.listenup.client.features.home

import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.presentation.home.HomeConnectivity
import com.calypsan.listenup.client.presentation.home.HomeSlot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Spec §2.1 (the order never changes), §3.1/§3.3 (notices) and §3.4 (wide arrangements), in one place. */
class HomeBlocksTest :
    FunSpec({
        val book =
            ContinueListeningBook(
                bookId = "a",
                title = "The Way of Kings",
                authorNames = "Brandon Sanderson",
                narratorNames = null,
                coverPath = null,
                progress = 0.11f,
                currentPositionMs = 1L,
                totalDurationMs = 2L,
                lastPlayedAtMs = 0L,
                isDownloaded = false,
            )
        val resume = HomeSlot.Resume(book = book, next = null, chapter = null, canPlay = true)
        val none = HomeNotice.None

        test("a loaded book on a phone: also in progress, stats, shelves — no slot") {
            homeBlocks(HomeSlot.Absent, none, hasAlsoInProgress = true, isArriving = false, HomeWidth.Compact) shouldBe
                listOf(HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("one book in progress and loaded: no also in progress section at all") {
            homeBlocks(HomeSlot.Absent, none, hasAlsoInProgress = false, isArriving = false, HomeWidth.Compact) shouldBe
                listOf(HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("nothing loaded on a phone: Resume first, then the fixed order") {
            homeBlocks(resume, none, hasAlsoInProgress = true, isArriving = false, HomeWidth.Compact) shouldBe
                listOf(HomeBlock.Resume, HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("a medium window keeps the phone order") {
            homeBlocks(resume, none, hasAlsoInProgress = true, isArriving = false, HomeWidth.Medium) shouldBe
                listOf(HomeBlock.Resume, HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("a loaded book on a tablet: also in progress, then stats beside shelves") {
            homeBlocks(HomeSlot.Absent, none, hasAlsoInProgress = true, isArriving = false, HomeWidth.Expanded) shouldBe
                listOf(HomeBlock.AlsoInProgress, HomeBlock.StatsBesideShelves)
        }

        test("nothing loaded on a tablet: the slot beside stats, then also in progress, then shelves") {
            homeBlocks(resume, none, hasAlsoInProgress = true, isArriving = false, HomeWidth.Expanded) shouldBe
                listOf(HomeBlock.SlotBesideStats, HomeBlock.AlsoInProgress, HomeBlock.Shelves)
        }

        test("every nothing-loaded kind leads, in the Resume position") {
            homeBlocks(finishedSlot(), none, hasAlsoInProgress = true, isArriving = false, HomeWidth.Compact) shouldBe
                listOf(HomeBlock.Finished, HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
            homeBlocks(nothingSlot(), none, hasAlsoInProgress = false, isArriving = false, HomeWidth.Compact) shouldBe
                listOf(HomeBlock.NothingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
            homeBlocks(welcomeSlot(), none, hasAlsoInProgress = false, isArriving = false, HomeWidth.Compact) shouldBe
                listOf(HomeBlock.Welcome, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("first sync puts the strip above a skeleton slot and keeps the rest in order") {
            homeBlocks(HomeSlot.FirstSync, none, hasAlsoInProgress = true, isArriving = true, HomeWidth.Compact) shouldBe
                listOf(
                    HomeBlock.FirstSyncStrip,
                    HomeBlock.FirstSync,
                    HomeBlock.AlsoInProgress,
                    HomeBlock.Stats,
                    HomeBlock.Shelves,
                )
        }

        test("the offline pill sits under the greeting and never reorders the rest") {
            homeBlocks(HomeSlot.Absent, HomeNotice.Offline, true, isArriving = false, HomeWidth.Compact) shouldBe
                listOf(HomeBlock.Offline, HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("a failed Continue Listening takes the slot position as one line") {
            homeBlocks(HomeSlot.Absent, HomeNotice.ContinueListeningFailed, false, false, HomeWidth.Compact) shouldBe
                listOf(HomeBlock.ContinueListeningFailed, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("on a tablet every slot sits beside the stats, as Resume does") {
            listOf(finishedSlot(), nothingSlot(), welcomeSlot(), HomeSlot.FirstSync).forEach { slot ->
                homeBlocks(slot, none, true, false, HomeWidth.Expanded).first() shouldBe HomeBlock.SlotBesideStats
            }
            homeBlocks(HomeSlot.Absent, none, true, false, HomeWidth.Expanded) shouldBe
                listOf(HomeBlock.AlsoInProgress, HomeBlock.StatsBesideShelves)
        }

        test("the notice follows the state: a failed Continue Listening outranks connectivity") {
            homeNotice(homeReady(HomeSlot.Absent, emptyList())) shouldBe HomeNotice.None
            homeNotice(
                homeReady(HomeSlot.Absent, emptyList(), connectivity = HomeConnectivity.ServerUnreachable),
            ) shouldBe HomeNotice.Offline
            homeNotice(
                homeReady(
                    HomeSlot.Absent,
                    emptyList(),
                    connectivity = HomeConnectivity.Offline,
                    continueListeningError = UnexpectedClientError(),
                ),
            ) shouldBe HomeNotice.ContinueListeningFailed
        }
    })
