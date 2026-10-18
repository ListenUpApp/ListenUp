package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.presentation.home.HomeConnectivity
import com.calypsan.listenup.client.presentation.home.HomeSlot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Spec §2.1 (the order never changes), §3.1/§3.3 (notices) and §3.4 (wide arrangements) — Android's twin. */
class HomeLayoutTest :
    FunSpec({
        val resume = resumeSlot("wok")
        val none = HomeNotice.None

        test("a loaded book on a phone: also in progress, stats, shelves — no slot") {
            homeBlocks(HomeSlot.Absent, none, hasAlsoInProgress = true, isArriving = false, isWide = false) shouldBe
                listOf(HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("one book in progress and loaded: no also in progress section at all") {
            homeBlocks(HomeSlot.Absent, none, hasAlsoInProgress = false, isArriving = false, isWide = false) shouldBe
                listOf(HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("nothing loaded on a phone: Resume first, then the fixed order") {
            homeBlocks(resume, none, hasAlsoInProgress = true, isArriving = false, isWide = false) shouldBe
                listOf(HomeBlock.Resume, HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("nothing loaded with nothing else in progress: Resume, stats, shelves") {
            homeBlocks(resume, none, hasAlsoInProgress = false, isArriving = false, isWide = false) shouldBe
                listOf(HomeBlock.Resume, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("a loaded book on a wide page: also in progress, then stats beside shelves") {
            homeBlocks(HomeSlot.Absent, none, hasAlsoInProgress = true, isArriving = false, isWide = true) shouldBe
                listOf(HomeBlock.AlsoInProgress, HomeBlock.StatsBesideShelves)
        }

        test("nothing loaded on a wide page: the slot beside stats, then also in progress, then shelves") {
            homeBlocks(resume, none, hasAlsoInProgress = true, isArriving = false, isWide = true) shouldBe
                listOf(HomeBlock.SlotBesideStats, HomeBlock.AlsoInProgress, HomeBlock.Shelves)
        }

        test("every nothing-loaded kind leads, in the Resume position") {
            homeBlocks(finishedSlot(), none, hasAlsoInProgress = true, isArriving = false, isWide = false) shouldBe
                listOf(HomeBlock.Finished, HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
            homeBlocks(nothingSlot(), none, hasAlsoInProgress = false, isArriving = false, isWide = false) shouldBe
                listOf(HomeBlock.NothingInProgress, HomeBlock.Stats, HomeBlock.Shelves)
            homeBlocks(welcomeSlot(), none, hasAlsoInProgress = false, isArriving = false, isWide = false) shouldBe
                listOf(HomeBlock.Welcome, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("first sync puts the strip above a skeleton slot and keeps the rest in order") {
            homeBlocks(HomeSlot.FirstSync, none, hasAlsoInProgress = true, isArriving = true, isWide = false) shouldBe
                listOf(
                    HomeBlock.FirstSyncStrip,
                    HomeBlock.FirstSync,
                    HomeBlock.AlsoInProgress,
                    HomeBlock.Stats,
                    HomeBlock.Shelves,
                )
        }

        test("the server notice sits under the greeting and never reorders the rest") {
            homeBlocks(HomeSlot.Absent, HomeNotice.Offline, true, isArriving = false, isWide = false) shouldBe
                listOf(HomeBlock.Offline, HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("a failed Continue Listening takes the slot position as one line") {
            homeBlocks(HomeSlot.Absent, HomeNotice.ContinueListeningFailed, false, false, isWide = false) shouldBe
                listOf(HomeBlock.ContinueListeningFailed, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("on a wide page every slot sits beside the stats, as Resume does") {
            listOf(finishedSlot(), nothingSlot(), welcomeSlot(), HomeSlot.FirstSync).forEach { slot ->
                homeBlocks(slot, none, true, false, isWide = true).first() shouldBe HomeBlock.SlotBesideStats
            }
        }

        test("the notice follows the state: a failed Continue Listening outranks connectivity") {
            homeNotice(readyHome()) shouldBe HomeNotice.None
            homeNotice(readyHome(connectivity = HomeConnectivity.Offline)) shouldBe HomeNotice.Offline
            homeNotice(
                readyHome(
                    connectivity = HomeConnectivity.ServerUnreachable,
                    continueListeningError = UnexpectedClientError(),
                ),
            ) shouldBe HomeNotice.ContinueListeningFailed
        }

        test("Home's words live in one object and mirror the catalog's English") {
            HomeWords.WELCOME_HEADLINE shouldBe "Pick something new"
            HomeWords.OFFLINE_NOTICE shouldBe "Can't reach your server."
            HomeWords.alsoFinished(listOf("Maya"), more = 0) shouldBe "Maya finished it too"
            HomeWords.alsoFinished(listOf("Maya", "Tom"), more = 0) shouldBe "Maya and Tom finished it too"
            HomeWords.alsoFinished(listOf("Maya"), more = 2) shouldBe "Maya and 2 more finished it too"
            HomeWords.alsoFinished(listOf("Maya", "Tom"), more = 3) shouldBe "Maya, Tom and 3 more finished it too"
            HomeWords.alsoFinished(emptyList(), more = 3) shouldBe null
        }

        test("a new listener is welcomed by name") {
            homeGreeting(com.calypsan.listenup.client.presentation.home.TimeOfDay.Evening, "Simon", isWelcome = true) shouldBe
                "Welcome, Simon"
        }
    })
