package com.calypsan.listenup.client.features.contributordetail

import androidx.compose.ui.unit.dp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The standalone books under a contributor's series are rows inside a lazy column, so their column
 * count is computed rather than left to a grid. It must match what `GridCells.Adaptive(140.dp)`
 * would give with 16dp gutters — the grid-only layout of the same screen — so the two layouts agree.
 */
class StandaloneGridColumnsTest :
    FunSpec({
        test("a phone fits two columns, the same as the grid-only layout") {
            // 411dp wide minus 24dp margins either side.
            standaloneGridColumns(availableWidth = 363.dp) shouldBe 2
        }

        test("a tablet fits more columns as it widens") {
            standaloneGridColumns(availableWidth = 752.dp) shouldBe 4
            standaloneGridColumns(availableWidth = 1200.dp) shouldBe 7
        }

        test("a cell exactly at the minimum with its gutter counts") {
            // Three 140dp cells and two 16dp gutters.
            standaloneGridColumns(availableWidth = 452.dp) shouldBe 3
            standaloneGridColumns(availableWidth = 451.dp) shouldBe 2
        }

        test("never fewer than one column, however narrow") {
            standaloneGridColumns(availableWidth = 80.dp) shouldBe 1
            standaloneGridColumns(availableWidth = 0.dp) shouldBe 1
        }
    })
