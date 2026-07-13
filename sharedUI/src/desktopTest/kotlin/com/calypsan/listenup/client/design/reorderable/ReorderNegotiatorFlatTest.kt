package com.calypsan.listenup.client.design.reorderable

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Flat-case coverage for [ReorderNegotiator.resolveMove]: every [ReorderNode.parentId] is null,
 * so every move stays a root-level reorder. This is the shape Reading Orders and any other
 * single-level list will drive the primitive with.
 */
class ReorderNegotiatorFlatTest :
    FunSpec({
        val flat =
            listOf(
                ReorderNode(id = "a", parentId = null, canHaveChildren = false),
                ReorderNode(id = "b", parentId = null, canHaveChildren = false),
                ReorderNode(id = "c", parentId = null, canHaveChildren = false),
            )

        test("dragging the first item to the last slot produces the move") {
            val move = ReorderNegotiator.resolveMove(flat, draggedId = "a", newParentId = null, targetIndex = 2)
            move shouldBe ReorderMove(movedId = "a", newParentId = null, newIndex = 2)
        }

        test("dragging an item to its own current slot is a no-op — returns null") {
            // "a" is already at index 0 among its siblings.
            val move = ReorderNegotiator.resolveMove(flat, draggedId = "a", newParentId = null, targetIndex = 0)
            move.shouldBeNull()
        }

        test("dragging the middle item one slot right produces the move") {
            val move = ReorderNegotiator.resolveMove(flat, draggedId = "b", newParentId = null, targetIndex = 2)
            move shouldBe ReorderMove(movedId = "b", newParentId = null, newIndex = 2)
        }

        test("a target index past the last sibling clamps to the last legal slot") {
            val move = ReorderNegotiator.resolveMove(flat, draggedId = "a", newParentId = null, targetIndex = 99)
            // 3 siblings total; "a" removed leaves 2 remaining, so the max landing index is 2.
            move shouldBe ReorderMove(movedId = "a", newParentId = null, newIndex = 2)
        }

        test("a negative target index clamps to zero") {
            val move = ReorderNegotiator.resolveMove(flat, draggedId = "c", newParentId = null, targetIndex = -5)
            move shouldBe ReorderMove(movedId = "c", newParentId = null, newIndex = 0)
        }

        test("dragging an id absent from nodes returns null") {
            val move = ReorderNegotiator.resolveMove(flat, draggedId = "ghost", newParentId = null, targetIndex = 0)
            move.shouldBeNull()
        }

        test("single-item list — every drop is a no-op") {
            val single = listOf(ReorderNode(id = "only", parentId = null, canHaveChildren = false))
            val move = ReorderNegotiator.resolveMove(single, draggedId = "only", newParentId = null, targetIndex = 0)
            move.shouldBeNull()
        }
    })
