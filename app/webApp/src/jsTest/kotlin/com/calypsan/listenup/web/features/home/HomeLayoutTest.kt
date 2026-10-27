package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.client.presentation.home.TimeOfDay
import com.calypsan.listenup.client.presentation.home.HomeSlot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Spec §2.1 (the order never changes), §3.1/§3.3 (notices) and §3.4 (wide arrangements) — Android's twin. */
class HomeLayoutTest :
    FunSpec({
        fun blocks(
            slot: HomeSlot,
            hasAlsoInProgress: Boolean,
            isWide: Boolean = false,
            isOffline: Boolean = false,
            continueListeningFailed: Boolean = false,
            showsStrip: Boolean = false,
        ) = homeBlocks(
            slot = slot,
            isOffline = isOffline,
            continueListeningFailed = continueListeningFailed,
            showsStrip = showsStrip,
            hasAlsoInProgress = hasAlsoInProgress,
            isWide = isWide,
        )

        test("a loaded book on a phone: also in progress, stats, shelves — no slot") {
            blocks(HomeSlot.Absent, hasAlsoInProgress = true) shouldBe
                listOf(HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("one book in progress and loaded: no also in progress section at all") {
            blocks(HomeSlot.Absent, hasAlsoInProgress = false) shouldBe listOf(HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("a loaded book on a wide page: also in progress, then stats beside shelves") {
            blocks(HomeSlot.Absent, hasAlsoInProgress = true, isWide = true) shouldBe
                listOf(HomeBlock.AlsoInProgress, HomeBlock.StatsBesideShelves)
        }

        test("a slot on a wide page: beside stats, then also in progress, then shelves") {
            blocks(finishedSlot(), hasAlsoInProgress = true, isWide = true) shouldBe
                listOf(HomeBlock.SlotBesideStats, HomeBlock.AlsoInProgress, HomeBlock.Shelves)
        }

        test("every slot kind leads, in the slot position") {
            blocks(finishedSlot(), hasAlsoInProgress = true) shouldBe
                listOf(HomeBlock.Finished, HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
            blocks(nothingSlot(), hasAlsoInProgress = false) shouldBe
                listOf(HomeBlock.NothingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
            blocks(welcomeSlot(), hasAlsoInProgress = false) shouldBe
                listOf(HomeBlock.Welcome, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("the strip sits above any slot, and after the notice (H5)") {
            blocks(HomeSlot.FirstSync, hasAlsoInProgress = true, showsStrip = true) shouldBe
                listOf(
                    HomeBlock.FirstSyncStrip,
                    HomeBlock.FirstSync,
                    HomeBlock.AlsoInProgress,
                    HomeBlock.Stats,
                    HomeBlock.Shelves,
                )
            blocks(finishedSlot(), hasAlsoInProgress = false, isOffline = true, showsStrip = true) shouldBe
                listOf(HomeBlock.Offline, HomeBlock.FirstSyncStrip, HomeBlock.Finished, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("offline and a failed Continue Listening are separate signals") {
            blocks(HomeSlot.Absent, hasAlsoInProgress = false, continueListeningFailed = true) shouldBe
                listOf(HomeBlock.ContinueListeningFailed, HomeBlock.Stats, HomeBlock.Shelves)
            blocks(HomeSlot.Absent, hasAlsoInProgress = false, isOffline = true, continueListeningFailed = true) shouldBe
                listOf(HomeBlock.Offline, HomeBlock.ContinueListeningFailed, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("on a wide page every slot sits beside the stats") {
            listOf(finishedSlot(), nothingSlot(), welcomeSlot(), HomeSlot.FirstSync).forEach { slot ->
                blocks(slot, hasAlsoInProgress = true, isWide = true).first() shouldBe HomeBlock.SlotBesideStats
            }
        }

        test("Home's words live in one object and mirror the catalog's English") {
            HomeWords.WELCOME_HEADLINE shouldBe "Pick something new"
            HomeWords.OFFLINE_NOTICE shouldBe "Can't reach your server."
            HomeWords.BUILDING_TITLE shouldBe "Building your library"
            HomeWords.arrivingTitle(done = 412, total = 1_280) shouldBe "Building your library · 412 of 1,280 books"
            HomeWords.alsoFinished(listOf("Maya"), more = 0) shouldBe "Maya finished it too"
            HomeWords.alsoFinished(listOf("Maya", "Tom"), more = 0) shouldBe "Maya and Tom finished it too"
            HomeWords.alsoFinished(listOf("Maya"), more = 2) shouldBe "Maya and 2 more finished it too"
            HomeWords.alsoFinished(listOf("Maya", "Tom"), more = 3) shouldBe "Maya, Tom and 3 more finished it too"
            HomeWords.alsoFinished(emptyList(), more = 3) shouldBe null
        }

        test("a new listener is welcomed by name") {
            homeGreeting(TimeOfDay.Evening, "Simon", isWelcome = true) shouldBe "Welcome, Simon"
        }
    })
