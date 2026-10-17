package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.client.presentation.home.HomeSlot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Spec §2.1 (the order never changes) and §3.4 (wide arrangements), in one place — Android's HomeBlocksTest twin. */
class HomeLayoutTest :
    FunSpec({
        val resume = resumeSlot("wok")

        test("a loaded book on a phone: also in progress, stats, shelves — no slot") {
            homeBlocks(HomeSlot.Absent, hasAlsoInProgress = true, isWide = false) shouldBe
                listOf(HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("one book in progress and loaded: no also in progress section at all") {
            homeBlocks(HomeSlot.Absent, hasAlsoInProgress = false, isWide = false) shouldBe
                listOf(HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("nothing loaded on a phone: Resume first, then the fixed order") {
            homeBlocks(resume, hasAlsoInProgress = true, isWide = false) shouldBe
                listOf(HomeBlock.Resume, HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("nothing loaded with nothing else in progress: Resume, stats, shelves") {
            homeBlocks(resume, hasAlsoInProgress = false, isWide = false) shouldBe
                listOf(HomeBlock.Resume, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("a loaded book on a wide page: also in progress, then stats beside shelves") {
            homeBlocks(HomeSlot.Absent, hasAlsoInProgress = true, isWide = true) shouldBe
                listOf(HomeBlock.AlsoInProgress, HomeBlock.StatsBesideShelves)
        }

        test("nothing loaded on a wide page: Resume beside stats, then also in progress, then shelves") {
            homeBlocks(resume, hasAlsoInProgress = true, isWide = true) shouldBe
                listOf(HomeBlock.ResumeBesideStats, HomeBlock.AlsoInProgress, HomeBlock.Shelves)
        }

        test("until PR 4, nothing in progress and a new listener show the interim empty state in the slot's place") {
            homeBlocks(HomeSlot.NothingInProgress(candidate = null), hasAlsoInProgress = false, isWide = false) shouldBe
                listOf(HomeBlock.NothingToResume, HomeBlock.Stats, HomeBlock.Shelves)
            homeBlocks(HomeSlot.NewListener(covers = emptyList()), hasAlsoInProgress = false, isWide = true) shouldBe
                listOf(HomeBlock.NothingToResume, HomeBlock.StatsBesideShelves)
        }

        test("until PR 4, just finished and first sync draw no slot") {
            homeBlocks(HomeSlot.FirstSync, hasAlsoInProgress = true, isWide = false) shouldBe
                listOf(HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }
    })
