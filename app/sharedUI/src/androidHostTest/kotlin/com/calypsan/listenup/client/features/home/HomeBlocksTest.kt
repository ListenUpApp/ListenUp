package com.calypsan.listenup.client.features.home

import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.presentation.home.HomeConnectivity
import com.calypsan.listenup.client.presentation.home.HomeSlot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Spec §2.1 (the order never changes), §3.1/§3.3 (notices) and §3.4 (wide arrangements), in one place. */
class HomeBlocksTest :
    FunSpec({
        fun blocks(
            slot: HomeSlot,
            hasEverythingInProgress: Boolean,
            width: HomeWidth = HomeWidth.Compact,
            isOffline: Boolean = false,
            continueListeningFailed: Boolean = false,
            showsStrip: Boolean = false,
        ) = homeBlocks(
            slot = slot,
            isOffline = isOffline,
            continueListeningFailed = continueListeningFailed,
            showsStrip = showsStrip,
            hasEverythingInProgress = hasEverythingInProgress,
            width = width,
        )

        test("a loaded book on a phone: everything in progress, stats, shelves — no slot") {
            blocks(HomeSlot.Absent, hasEverythingInProgress = true) shouldBe
                listOf(HomeBlock.EverythingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("an empty list: no Everything in progress section at all") {
            blocks(HomeSlot.Absent, hasEverythingInProgress = false) shouldBe listOf(HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("a medium window keeps the phone order") {
            blocks(finishedSlot(), hasEverythingInProgress = true, width = HomeWidth.Medium) shouldBe
                listOf(HomeBlock.Finished, HomeBlock.EverythingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("a loaded book on a tablet: everything in progress, then stats beside shelves") {
            blocks(HomeSlot.Absent, hasEverythingInProgress = true, width = HomeWidth.Expanded) shouldBe
                listOf(HomeBlock.EverythingInProgress, HomeBlock.StatsBesideShelves)
        }

        test("a slot on a tablet: beside stats, then everything in progress, then shelves") {
            blocks(finishedSlot(), hasEverythingInProgress = true, width = HomeWidth.Expanded) shouldBe
                listOf(HomeBlock.SlotBesideStats, HomeBlock.EverythingInProgress, HomeBlock.Shelves)
        }

        test("every slot kind leads, in the slot position") {
            blocks(finishedSlot(), hasEverythingInProgress = true) shouldBe
                listOf(HomeBlock.Finished, HomeBlock.EverythingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
            blocks(nothingSlot(), hasEverythingInProgress = false) shouldBe
                listOf(HomeBlock.NothingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
            blocks(welcomeSlot(), hasEverythingInProgress = false) shouldBe
                listOf(HomeBlock.Welcome, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("first sync puts the strip above a skeleton slot and keeps the rest in order") {
            blocks(HomeSlot.FirstSync, hasEverythingInProgress = true, showsStrip = true) shouldBe
                listOf(
                    HomeBlock.FirstSyncStrip,
                    HomeBlock.FirstSync,
                    HomeBlock.EverythingInProgress,
                    HomeBlock.Stats,
                    HomeBlock.Shelves,
                )
        }

        test("a scan or an arriving library shows the strip above any slot, and with no slot at all (H5)") {
            blocks(finishedSlot(), hasEverythingInProgress = false, showsStrip = true) shouldBe
                listOf(HomeBlock.FirstSyncStrip, HomeBlock.Finished, HomeBlock.Stats, HomeBlock.Shelves)
            blocks(HomeSlot.Absent, hasEverythingInProgress = true, showsStrip = true) shouldBe
                listOf(HomeBlock.FirstSyncStrip, HomeBlock.EverythingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("the offline pill sits under the greeting, above the strip, and never reorders the rest") {
            blocks(HomeSlot.Absent, hasEverythingInProgress = true, isOffline = true) shouldBe
                listOf(HomeBlock.Offline, HomeBlock.EverythingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
            blocks(finishedSlot(), hasEverythingInProgress = false, isOffline = true, showsStrip = true) shouldBe
                listOf(HomeBlock.Offline, HomeBlock.FirstSyncStrip, HomeBlock.Finished, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("a failed Continue Listening takes the slot position as one line") {
            blocks(HomeSlot.Absent, hasEverythingInProgress = false, continueListeningFailed = true) shouldBe
                listOf(HomeBlock.ContinueListeningFailed, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("offline and a failed Continue Listening both show: they are separate signals") {
            blocks(HomeSlot.Absent, hasEverythingInProgress = false, isOffline = true, continueListeningFailed = true) shouldBe
                listOf(HomeBlock.Offline, HomeBlock.ContinueListeningFailed, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("on a tablet every slot sits beside the stats") {
            listOf(finishedSlot(), nothingSlot(), welcomeSlot(), HomeSlot.FirstSync).forEach { slot ->
                blocks(slot, hasEverythingInProgress = true, width = HomeWidth.Expanded).first() shouldBe
                    HomeBlock.SlotBesideStats
            }
        }

        test("the state's signals: offline is any connectivity but online; the strip is arriving or scanning") {
            homeReady(HomeSlot.Absent, emptyList()).isOffline shouldBe false
            homeReady(HomeSlot.Absent, emptyList(), connectivity = HomeConnectivity.ServerUnreachable).isOffline shouldBe true
            homeReady(HomeSlot.Absent, emptyList(), connectivity = HomeConnectivity.Offline).isOffline shouldBe true
            homeReady(HomeSlot.Absent, emptyList()).showsArrivalStrip shouldBe false
            homeReady(HomeSlot.Absent, emptyList(), isLibraryArriving = true).showsArrivalStrip shouldBe true
            homeReady(HomeSlot.Absent, emptyList(), scanProgress = scan()).showsArrivalStrip shouldBe true
        }
    })

private fun scan() = ScanProgressState(phase = "analyzing", current = 1, total = 2, added = 0, updated = 0, removed = 0)
