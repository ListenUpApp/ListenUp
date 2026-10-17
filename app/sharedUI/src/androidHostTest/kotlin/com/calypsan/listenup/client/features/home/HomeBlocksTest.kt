package com.calypsan.listenup.client.features.home

import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.presentation.home.HomeSlot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Spec §2.1 (the order never changes) and §3.4 (wide arrangements), in one place. */
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

        test("a loaded book on a phone: also in progress, stats, shelves — no slot") {
            homeBlocks(HomeSlot.Absent, hasAlsoInProgress = true, HomeWidth.Compact) shouldBe
                listOf(HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("one book in progress and loaded: no also in progress section at all") {
            homeBlocks(HomeSlot.Absent, hasAlsoInProgress = false, HomeWidth.Compact) shouldBe
                listOf(HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("nothing loaded on a phone: Resume first, then the fixed order") {
            homeBlocks(resume, hasAlsoInProgress = true, HomeWidth.Compact) shouldBe
                listOf(HomeBlock.Resume, HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("a medium window keeps the phone order") {
            homeBlocks(resume, hasAlsoInProgress = true, HomeWidth.Medium) shouldBe
                listOf(HomeBlock.Resume, HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }

        test("a loaded book on a tablet: also in progress, then stats beside shelves") {
            homeBlocks(HomeSlot.Absent, hasAlsoInProgress = true, HomeWidth.Expanded) shouldBe
                listOf(HomeBlock.AlsoInProgress, HomeBlock.StatsBesideShelves)
        }

        test("nothing loaded on a tablet: Resume beside stats, then also in progress, then shelves") {
            homeBlocks(resume, hasAlsoInProgress = true, HomeWidth.Expanded) shouldBe
                listOf(HomeBlock.ResumeBesideStats, HomeBlock.AlsoInProgress, HomeBlock.Shelves)
        }

        test("until PR 4, nothing in progress and a new listener show the interim empty state in the slot's place") {
            homeBlocks(HomeSlot.NothingInProgress(candidate = null), hasAlsoInProgress = false, HomeWidth.Compact) shouldBe
                listOf(HomeBlock.NothingToResume, HomeBlock.Stats, HomeBlock.Shelves)
            homeBlocks(HomeSlot.NewListener(covers = emptyList()), hasAlsoInProgress = false, HomeWidth.Expanded) shouldBe
                listOf(HomeBlock.NothingToResume, HomeBlock.StatsBesideShelves)
        }

        test("until PR 4, just finished and first sync draw no slot") {
            homeBlocks(HomeSlot.FirstSync, hasAlsoInProgress = true, HomeWidth.Compact) shouldBe
                listOf(HomeBlock.AlsoInProgress, HomeBlock.Stats, HomeBlock.Shelves)
        }
    })
