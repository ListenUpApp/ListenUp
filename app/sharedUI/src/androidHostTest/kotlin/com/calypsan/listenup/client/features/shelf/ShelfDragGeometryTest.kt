package com.calypsan.listenup.client.features.shelf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** A 2x2 grid of 100x100 cells with no gap, laid out left-to-right then top-to-bottom. */
private val grid =
    listOf(
        ShelfCellBounds("a", left = 0f, top = 0f, right = 100f, bottom = 100f),
        ShelfCellBounds("b", left = 100f, top = 0f, right = 200f, bottom = 100f),
        ShelfCellBounds("c", left = 0f, top = 100f, right = 100f, bottom = 200f),
    )

/**
 * Where a drop lands, in a grid rather than a list.
 *
 * A list only has to answer "which row"; a grid has to answer it in two dimensions, and its last
 * row is usually short — which is the case that decides whether a clumsy drop reorders the shelf or
 * leaves it alone.
 */
class ShelfDragGeometryTest :
    FunSpec({

        test("a pointer inside a cell finds that cell") {
            cellKeyAt(grid, x = 50f, y = 50f) shouldBe "a"
            cellKeyAt(grid, x = 150f, y = 50f) shouldBe "b"
            cellKeyAt(grid, x = 50f, y = 150f) shouldBe "c"
        }

        test("the gap beside a short last row belongs to nobody") {
            // The 2x2 grid has only three books, so the fourth slot is empty space. A drop there
            // must not fall through to whichever cell happens to be nearest.
            cellKeyAt(grid, x = 150f, y = 150f) shouldBe null
        }

        test("a pointer outside the grid entirely finds nothing") {
            cellKeyAt(grid, x = -10f, y = 50f) shouldBe null
            cellKeyAt(grid, x = 50f, y = 900f) shouldBe null
        }

        test("adjacent cells never both claim the pixel between them") {
            // Half-open bounds. Were both edges inclusive, this pixel's owner would depend on the
            // order the cells happen to be listed in rather than on where it is.
            cellKeyAt(grid, x = 100f, y = 50f) shouldBe "b"
            cellKeyAt(grid, x = 99.9f, y = 50f) shouldBe "a"
            cellKeyAt(grid, x = 50f, y = 100f) shouldBe "c"
        }

        test("an empty grid answers nothing rather than throwing") {
            cellKeyAt(emptyList(), x = 0f, y = 0f) shouldBe null
        }
    })
