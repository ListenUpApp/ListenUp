package com.calypsan.listenup.client.presentation.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Spec §2.1 (the order never changes), §3.1/§3.3 (notices) and §3.4 (wide arrangements), in one place. */
class HomeBlocksTest :
    FunSpec({
        val finished =
            HomeSlot.JustFinished(
                FinishedCard(
                    book = FinishedBook("wok", "The Way of Kings", "Brandon Sanderson", null, null, 163_800_000L),
                    startedAtMs = null,
                    finishedAtMs = 0L,
                    yourHalfStars = null,
                    next = null,
                    alsoFinished = AlsoFinished(names = emptyList(), othersCount = 0),
                ),
            )
        val nothing = HomeSlot.NothingInProgress(candidate = null)
        val welcome = HomeSlot.NewListener(covers = emptyList())

        fun blocks(
            slot: HomeSlot,
            hasEverythingInProgress: Boolean,
            isWide: Boolean = false,
            isOffline: Boolean = false,
            continueListeningFailed: Boolean = false,
            showsStrip: Boolean = false,
        ) = homeBlocks(
            slot = slot,
            isOffline = isOffline,
            continueListeningFailed = continueListeningFailed,
            showsStrip = showsStrip,
            hasEverythingInProgress = hasEverythingInProgress,
            isWide = isWide,
        )

        test("a loaded book on a phone: everything in progress, stats, shelves — no slot") {
            blocks(HomeSlot.Absent, hasEverythingInProgress = true) shouldBe
                listOf(HomeBlock.EverythingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("an empty list: no Everything in progress section at all") {
            blocks(HomeSlot.Absent, hasEverythingInProgress = false) shouldBe listOf(HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("a loaded book on a wide window: everything in progress, then stats beside shelves") {
            blocks(HomeSlot.Absent, hasEverythingInProgress = true, isWide = true) shouldBe
                listOf(HomeBlock.EverythingInProgress, HomeBlock.StatsBesideShelves)
        }

        test("a slot on a wide window: beside stats, then everything in progress, then shelves") {
            blocks(finished, hasEverythingInProgress = true, isWide = true) shouldBe
                listOf(HomeBlock.SlotBesideStats, HomeBlock.EverythingInProgress, HomeBlock.Shelves)
        }

        test("every slot kind leads, in the slot position") {
            blocks(finished, hasEverythingInProgress = true) shouldBe
                listOf(HomeBlock.Finished, HomeBlock.EverythingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
            blocks(nothing, hasEverythingInProgress = false) shouldBe
                listOf(HomeBlock.NothingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
            blocks(welcome, hasEverythingInProgress = false) shouldBe
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
            blocks(finished, hasEverythingInProgress = false, showsStrip = true) shouldBe
                listOf(HomeBlock.FirstSyncStrip, HomeBlock.Finished, HomeBlock.Stats, HomeBlock.Shelves)
            blocks(HomeSlot.Absent, hasEverythingInProgress = true, showsStrip = true) shouldBe
                listOf(HomeBlock.FirstSyncStrip, HomeBlock.EverythingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("the offline pill sits under the greeting, above the strip, and never reorders the rest") {
            blocks(HomeSlot.Absent, hasEverythingInProgress = true, isOffline = true) shouldBe
                listOf(HomeBlock.Offline, HomeBlock.EverythingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
            blocks(finished, hasEverythingInProgress = false, isOffline = true, showsStrip = true) shouldBe
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

        test("on a wide window every slot sits beside the stats") {
            listOf(finished, nothing, welcome, HomeSlot.FirstSync).forEach { slot ->
                blocks(slot, hasEverythingInProgress = true, isWide = true).first() shouldBe HomeBlock.SlotBesideStats
            }
        }
    })
