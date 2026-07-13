package com.calypsan.listenup.client.design.reorderable

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Nested-case coverage for [ReorderNegotiator.resolveMove]: a two-level tree (a "Part" header
 * with child chapters), the shape Plan D's chapter Structure lens will drive the primitive with.
 */
class ReorderNegotiatorNestedTest :
    FunSpec({
        // partA (header, can have children)
        //   ├── ch1
        //   └── ch2
        // partB (header, can have children)
        //   └── ch3
        // loose (leaf, cannot have children — e.g. an ungrouped chapter)
        val tree =
            listOf(
                ReorderNode(id = "partA", parentId = null, canHaveChildren = true),
                ReorderNode(id = "ch1", parentId = "partA", canHaveChildren = false),
                ReorderNode(id = "ch2", parentId = "partA", canHaveChildren = false),
                ReorderNode(id = "partB", parentId = null, canHaveChildren = true),
                ReorderNode(id = "ch3", parentId = "partB", canHaveChildren = false),
                ReorderNode(id = "loose", parentId = null, canHaveChildren = false),
            )

        test("dropping a leaf under a different parent produces a reparent move") {
            val move = ReorderNegotiator.resolveMove(tree, draggedId = "ch1", newParentId = "partB", targetIndex = 0)
            move shouldBe ReorderMove(movedId = "ch1", newParentId = "partB", newIndex = 0)
        }

        test("dropping a leaf under a different parent at the end appends it") {
            // partB already has "ch3" as a child; landing at index 1 puts it after ch3.
            val move = ReorderNegotiator.resolveMove(tree, draggedId = "ch1", newParentId = "partB", targetIndex = 1)
            move shouldBe ReorderMove(movedId = "ch1", newParentId = "partB", newIndex = 1)
        }

        test("dropping a header under itself is illegal — returns null") {
            val move = ReorderNegotiator.resolveMove(tree, draggedId = "partA", newParentId = "partA", targetIndex = 0)
            move.shouldBeNull()
        }

        test("dropping a header under its own descendant is illegal — returns null") {
            // ch1 is a child of partA — dropping partA under ch1 would create a cycle.
            val move = ReorderNegotiator.resolveMove(tree, draggedId = "partA", newParentId = "ch1", targetIndex = 0)
            move.shouldBeNull()
        }

        test("dropping under a node with canHaveChildren = false is illegal — returns null") {
            val move = ReorderNegotiator.resolveMove(tree, draggedId = "partB", newParentId = "loose", targetIndex = 0)
            move.shouldBeNull()
        }

        test("reparenting a header (with its own children) to root-level is legal") {
            val move = ReorderNegotiator.resolveMove(tree, draggedId = "partB", newParentId = null, targetIndex = 0)
            move shouldBe ReorderMove(movedId = "partB", newParentId = null, newIndex = 0)
        }

        test("dropping a leaf back under its own current parent, same slot, is a no-op") {
            // ch2 is already the second child of partA (index 1 among {ch1, ch2}).
            val move = ReorderNegotiator.resolveMove(tree, draggedId = "ch2", newParentId = "partA", targetIndex = 1)
            move.shouldBeNull()
        }

        test("reordering within the same parent to a different slot produces the move") {
            val move = ReorderNegotiator.resolveMove(tree, draggedId = "ch2", newParentId = "partA", targetIndex = 0)
            move shouldBe ReorderMove(movedId = "ch2", newParentId = "partA", newIndex = 0)
        }
    })
