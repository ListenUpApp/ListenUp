package com.calypsan.listenup.domain.series

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class SeriesTreeTest :
    FunSpec({
        // cosmere ─┬─ mistborn ─┬─ era1
        //          │            └─ era2
        //          └─ stormlight
        // narnia (flat)
        val tree =
            SeriesTree(
                listOf(
                    SeriesNode("cosmere", parentId = null, parentPosition = null),
                    SeriesNode("stormlight", parentId = "cosmere", parentPosition = 1),
                    SeriesNode("mistborn", parentId = "cosmere", parentPosition = 0),
                    SeriesNode("era2", parentId = "mistborn", parentPosition = 1),
                    SeriesNode("era1", parentId = "mistborn", parentPosition = 0),
                    SeriesNode("narnia", parentId = null, parentPosition = null),
                ),
            )

        test("ancestors are listed root first and exclude the series itself") {
            tree.ancestorsOf("era1") shouldContainExactly listOf("cosmere", "mistborn")
            tree.ancestorsOf("cosmere").shouldBeEmpty()
        }

        test("children come back in sibling position order") {
            tree.childrenOf("cosmere") shouldContainExactly listOf("mistborn", "stormlight")
            tree.childrenOf("narnia").shouldBeEmpty()
        }

        test("children without a position sort last, by id") {
            val loose =
                SeriesTree(
                    listOf(
                        SeriesNode("p", null, null),
                        SeriesNode("b", "p", null),
                        SeriesNode("a", "p", null),
                        SeriesNode("z", "p", 0),
                    ),
                )
            loose.childrenOf("p") shouldContainExactly listOf("z", "a", "b")
        }

        test("a subtree contains the series and every descendant") {
            tree.subtreeOf("mistborn") shouldContainExactlyInAnyOrder listOf("mistborn", "era1", "era2")
            tree.subtreeOf("narnia") shouldContainExactlyInAnyOrder listOf("narnia")
        }

        test("a series whose parent is unknown is a root") {
            val orphan = SeriesTree(listOf(SeriesNode("child", parentId = "gone", parentPosition = 0)))
            orphan.ancestorsOf("child").shouldBeEmpty()
        }

        test("moving a series under itself or a descendant would cycle") {
            tree.wouldCycle(id = "cosmere", newParentId = "cosmere") shouldBe true
            tree.wouldCycle(id = "cosmere", newParentId = "era1") shouldBe true
            tree.wouldCycle(id = "era1", newParentId = "stormlight") shouldBe false
            tree.wouldCycle(id = "narnia", newParentId = "cosmere") shouldBe false
        }

        test("a corrupt cycle in the data never hangs a walk") {
            val corrupt = SeriesTree(listOf(SeriesNode("a", "b", 0), SeriesNode("b", "a", 0)))
            corrupt.ancestorsOf("a") shouldContainExactly listOf("b")
            corrupt.subtreeOf("a") shouldContainExactlyInAnyOrder listOf("a", "b")
        }

        test("the next child position is one past the highest in use") {
            tree.nextChildPosition("cosmere") shouldBe 2
            tree.nextChildPosition("narnia") shouldBe 0
        }

        test("default order walks sub-series in sibling order, then the parent's own books") {
            val memberships =
                listOf(
                    SeriesMembership("warbreaker", "cosmere", sequence = null),
                    SeriesMembership("elantris", "cosmere", sequence = 1.0),
                    SeriesMembership("way-of-kings", "stormlight", 1.0),
                    SeriesMembership("alloy", "era2", 1.0),
                    SeriesMembership("hero", "era1", 3.0),
                    SeriesMembership("final-empire", "era1", 1.0),
                    SeriesMembership("well", "era1", 2.0),
                )
            tree.defaultBookOrder("cosmere", memberships) shouldContainExactly
                listOf("final-empire", "well", "hero", "alloy", "way-of-kings", "elantris", "warbreaker")
        }

        test("a book reachable twice appears once, at its deepest membership") {
            val memberships =
                listOf(
                    SeriesMembership("final-empire", "cosmere", 1.0),
                    SeriesMembership("final-empire", "era1", 1.0),
                    SeriesMembership("elantris", "cosmere", 2.0),
                )
            tree.defaultBookOrder("cosmere", memberships) shouldContainExactly listOf("final-empire", "elantris")
        }

        test("a book in two sibling sub-series is listed once on the parent, under the first, but counts in both") {
            val memberships =
                listOf(
                    SeriesMembership("crossover", "stormlight", 2.0),
                    SeriesMembership("crossover", "mistborn", 5.0),
                    SeriesMembership("way-of-kings", "stormlight", 1.0),
                )
            tree.defaultBookOrder("cosmere", memberships) shouldContainExactly listOf("crossover", "way-of-kings")
            tree.defaultBookOrder("mistborn", memberships) shouldContainExactly listOf("crossover")
            tree.defaultBookOrder("stormlight", memberships) shouldContainExactly listOf("way-of-kings", "crossover")
        }

        test("a flat series orders by sequence, unnumbered last, ties in input order") {
            val memberships =
                listOf(
                    SeriesMembership("c", "narnia", null),
                    SeriesMembership("b", "narnia", 2.0),
                    SeriesMembership("a2", "narnia", 1.0),
                    SeriesMembership("a1", "narnia", 1.0),
                )
            tree.defaultBookOrder("narnia", memberships) shouldContainExactly listOf("a2", "a1", "b", "c")
        }
    })
