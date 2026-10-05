package com.calypsan.listenup.domain.series

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.random.Random

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

        test("a series placed at the next child position sorts last among positioned siblings") {
            val siblings =
                listOf(
                    SeriesNode("cosmere", null, null),
                    SeriesNode("mistborn", "cosmere", 0),
                    SeriesNode("stormlight", "cosmere", 4),
                )
            val next = SeriesTree(siblings).nextChildPosition("cosmere")
            next shouldBe 5
            SeriesTree(siblings + SeriesNode("a-new-one", "cosmere", next)).childrenOf("cosmere") shouldContainExactly
                listOf("mistborn", "stormlight", "a-new-one")
        }

        test("the next child position ignores unpositioned siblings, which still sort after it") {
            // An unpositioned sibling sorts after EVERY position, so no position can append behind
            // one: a caller that needs a true append must give those siblings positions first.
            val loose =
                listOf(
                    SeriesNode("p", null, null),
                    SeriesNode("b", "p", null),
                    SeriesNode("a", "p", null),
                )
            SeriesTree(loose).nextChildPosition("p") shouldBe 0
            SeriesTree(loose + SeriesNode("z", "p", 3)).nextChildPosition("p") shouldBe 4
            SeriesTree(loose + SeriesNode("new", "p", 0)).childrenOf("p") shouldContainExactly listOf("new", "a", "b")
        }

        test("a corrupt cycle never hangs the book order or the cycle check") {
            val corrupt = SeriesTree(listOf(SeriesNode("a", "b", 0), SeriesNode("b", "a", 0)))
            val memberships = listOf(SeriesMembership("in-a", "a", 1.0), SeriesMembership("in-b", "b", 1.0))
            corrupt.defaultBookOrder("a", memberships) shouldContainExactly listOf("in-b", "in-a")
            corrupt.wouldCycle(id = "a", newParentId = "b") shouldBe true
        }

        test("a series that names itself as its parent is a root with no sub-series") {
            val selfParent = SeriesTree(listOf(SeriesNode("s", "s", 0), SeriesNode("other", null, null)))
            selfParent.ancestorsOf("s").shouldBeEmpty()
            selfParent.childrenOf("s").shouldBeEmpty()
            selfParent.subtreeOf("s") shouldContainExactlyInAnyOrder listOf("s")
            selfParent.nextChildPosition("s") shouldBe 0
            selfParent.wouldCycle(id = "s", newParentId = "s") shouldBe true
            selfParent.wouldCycle(id = "s", newParentId = "other") shouldBe false
            selfParent.defaultBookOrder("s", listOf(SeriesMembership("book", "s", 1.0))) shouldContainExactly listOf("book")
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

        test("a book in a series and in its sub-series appears once, with the sub-series") {
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

        test("a book in two branches is listed where the walk reaches it first, not at its deepest membership") {
            // cosmere ─┬─ a
            //          └─ b ── b1
            val branches =
                SeriesTree(
                    listOf(
                        SeriesNode("cosmere", null, null),
                        SeriesNode("a", "cosmere", 0),
                        SeriesNode("b", "cosmere", 1),
                        SeriesNode("b1", "b", 0),
                    ),
                )
            val memberships =
                listOf(
                    SeriesMembership("only-a", "a", 1.0),
                    SeriesMembership("shared", "a", 2.0),
                    SeriesMembership("shared", "b1", 1.0),
                    SeriesMembership("only-b1", "b1", 2.0),
                )
            branches.defaultBookOrder("cosmere", memberships) shouldContainExactly listOf("only-a", "shared", "only-b1")
        }

        test("a flat series orders by sequence, unnumbered last, ties by sort key and then book id") {
            val memberships =
                listOf(
                    SeriesMembership("c", "narnia", null, sortKey = "Zebra"),
                    SeriesMembership("d", "narnia", null, sortKey = "Aslan"),
                    SeriesMembership("b", "narnia", 2.0),
                    SeriesMembership("a2", "narnia", 1.0, sortKey = "Prince Caspian"),
                    SeriesMembership("a1", "narnia", 1.0, sortKey = "The Silver Chair"),
                    SeriesMembership("a4", "narnia", 1.0, sortKey = "The Horse and His Boy"),
                    SeriesMembership("a3", "narnia", 1.0, sortKey = "The Horse and His Boy"),
                )
            tree.defaultBookOrder("narnia", memberships) shouldContainExactly listOf("a2", "a3", "a4", "a1", "b", "d", "c")
        }

        test("a membership without a sort key breaks ties by book id") {
            val memberships =
                listOf(
                    SeriesMembership("a2", "narnia", 1.0),
                    SeriesMembership("a1", "narnia", 1.0),
                )
            tree.defaultBookOrder("narnia", memberships) shouldContainExactly listOf("a1", "a2")
        }

        test("book groups attribute each book to the series the walk reaches it in, in post-order") {
            val memberships =
                listOf(
                    SeriesMembership("warbreaker", "cosmere", sequence = null),
                    SeriesMembership("way-of-kings", "stormlight", 1.0),
                    SeriesMembership("alloy", "era2", 1.0),
                    SeriesMembership("final-empire", "era1", 1.0),
                    SeriesMembership("final-empire", "mistborn", 1.0),
                    SeriesMembership("secret-history", "mistborn", 4.0),
                )
            val groups = tree.defaultBookGroups("cosmere", memberships)
            groups.keys.toList() shouldContainExactly listOf("era1", "era2", "mistborn", "stormlight", "cosmere")
            groups["era1"] shouldBe listOf("final-empire")
            groups["era2"] shouldBe listOf("alloy")
            groups["mistborn"] shouldBe listOf("secret-history")
            groups["stormlight"] shouldBe listOf("way-of-kings")
            groups["cosmere"] shouldBe listOf("warbreaker")
        }

        test("a series with no books of its own still has a group, so the caller sees the whole shape") {
            val groups = tree.defaultBookGroups("mistborn", listOf(SeriesMembership("hero", "era1", 3.0)))
            groups.keys.toList() shouldContainExactly listOf("era1", "era2", "mistborn")
            groups["era2"].shouldBeEmpty()
            groups["mistborn"].shouldBeEmpty()
        }

        test("flattening the book groups is exactly the default book order, whatever the input order") {
            val memberships =
                listOf(
                    SeriesMembership("crossover", "stormlight", 2.0),
                    SeriesMembership("crossover", "era2", 5.0),
                    SeriesMembership("way-of-kings", "stormlight", 1.0),
                    SeriesMembership("hero", "era1", 3.0),
                    SeriesMembership("final-empire", "era1", 1.0),
                    SeriesMembership("final-empire", "cosmere", 9.0),
                    SeriesMembership("elantris", "cosmere", null, sortKey = "Elantris"),
                )
            repeat(20) { seed ->
                val shuffled = memberships.shuffled(Random(seed))
                tree.defaultBookGroups("cosmere", shuffled).values.flatten() shouldContainExactly
                    tree.defaultBookOrder("cosmere", shuffled)
            }
        }

        test("the book order does not depend on the order the memberships arrive in") {
            val memberships =
                listOf(
                    SeriesMembership("warbreaker", "cosmere", null, sortKey = "Warbreaker"),
                    SeriesMembership("elantris", "cosmere", null, sortKey = "Elantris"),
                    SeriesMembership("way-of-kings", "stormlight", 1.0, sortKey = "The Way of Kings"),
                    SeriesMembership("edgedancer", "stormlight", 1.0, sortKey = "Edgedancer"),
                    SeriesMembership("hero", "era1", 3.0),
                    SeriesMembership("final-empire", "era1", 1.0),
                    SeriesMembership("crossover", "era2", 1.0),
                    SeriesMembership("crossover", "stormlight", 1.0, sortKey = "Crossover"),
                )
            val expected =
                listOf("final-empire", "hero", "crossover", "edgedancer", "way-of-kings", "elantris", "warbreaker")
            tree.defaultBookOrder("cosmere", memberships) shouldContainExactly expected
            repeat(20) { seed ->
                tree.defaultBookOrder("cosmere", memberships.shuffled(Random(seed))) shouldContainExactly expected
            }
        }
    })
