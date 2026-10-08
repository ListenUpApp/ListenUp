package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.core.SeriesId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class SeriesHierarchyTest :
    FunSpec({
        fun series(
            id: String,
            parent: String? = null,
            position: Int? = null,
            name: String = id.replaceFirstChar { it.uppercase() },
        ) = Series(id = SeriesId(id), name = name, parentId = parent?.let(::SeriesId), parentPosition = position)

        // cosmere ─┬─ mistborn ─┬─ era1
        //          │            └─ era2
        //          └─ stormlight
        val hierarchy =
            SeriesHierarchy(
                series =
                    listOf(
                        series("cosmere"),
                        series("mistborn", "cosmere", 0),
                        series("stormlight", "cosmere", 1),
                        series("era1", "mistborn", 0, name = "Mistborn Era 1"),
                        series("era2", "mistborn", 1),
                        series("dune"),
                    ),
                memberships =
                    listOf(
                        SeriesBookRef("era1", "final-empire"),
                        SeriesBookRef("era1", "well"),
                        SeriesBookRef("mistborn", "final-empire"),
                        SeriesBookRef("era2", "alloy"),
                        SeriesBookRef("stormlight", "way-of-kings"),
                        SeriesBookRef("cosmere", "warbreaker"),
                    ),
            )

        test("a path lists the series above, root first") {
            hierarchy.pathNames("era1") shouldContainExactly listOf("Cosmere", "Mistborn")
            hierarchy.ancestorsOf("cosmere").shouldBeEmpty()
        }

        test("children come in sibling order") {
            hierarchy.childrenOf("cosmere").map { it.id.value } shouldContainExactly listOf("mistborn", "stormlight")
        }

        test("a subtree's book count counts a book in two of its series once") {
            hierarchy.bookCount("mistborn") shouldBe 3
            hierarchy.bookCount("cosmere") shouldBe 5
            hierarchy.bookCount("era1") shouldBe 2
            hierarchy.bookCount("dune") shouldBe 0
        }

        test("the roots are the top-level series, by name") {
            hierarchy.roots.map { it.id.value } shouldContainExactly listOf("cosmere", "dune")
            hierarchy.isRoot("era1") shouldBe false
        }

        test("a series whose parent is not live is a root") {
            val orphaned = SeriesHierarchy(listOf(series("child", "gone", 0)), emptyList())
            orphaned.isRoot("child") shouldBe true
            orphaned.pathNames("child").shouldBeEmpty()
        }

        test("moving a series under its own sub-series would cycle") {
            hierarchy.wouldCycle("mistborn", "era1") shouldBe true
            hierarchy.wouldCycle("era1", "stormlight") shouldBe false
        }

        test("a name is found ignoring case and surrounding space") {
            hierarchy.findByName("  cosmere ")?.run { id.value } shouldBe "cosmere"
            hierarchy.findByName("Discworld") shouldBe null
            hierarchy.findByName(" ") shouldBe null
        }
    })
