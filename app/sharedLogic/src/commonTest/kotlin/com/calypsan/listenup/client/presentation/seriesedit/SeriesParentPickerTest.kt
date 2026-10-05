package com.calypsan.listenup.client.presentation.seriesedit

import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesBookRef
import com.calypsan.listenup.client.domain.model.SeriesHierarchy
import com.calypsan.listenup.core.SeriesId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class SeriesParentPickerTest :
    FunSpec({
        fun series(
            id: String,
            parent: String? = null,
            position: Int? = null,
        ) = Series(id = SeriesId(id), name = id, parentId = parent?.let(::SeriesId), parentPosition = position)

        val hierarchy =
            SeriesHierarchy(
                listOf(
                    series("Cosmere"),
                    series("Mistborn", parent = "Cosmere", position = 1),
                    series("Stormlight", parent = "Cosmere", position = 0),
                    series("Era 1", parent = "Mistborn", position = 0),
                    series("Narnia"),
                ),
                listOf(SeriesBookRef("Era 1", "b1"), SeriesBookRef("Stormlight", "b2"), SeriesBookRef("Mistborn", "b1")),
            )

        fun rows(
            currentId: String,
            query: String = "",
            expanded: Set<String> = initiallyExpanded(hierarchy, currentId),
        ) = parentPickerRows(hierarchy, currentId, query, expanded)

        test("a series and everything inside it stay in the tree, disabled — the loop can't be chosen") {
            rows("Cosmere").map { it.name to it.disabledReason } shouldContainExactly
                listOf(
                    "Cosmere" to ParentPickerDisabledReason.THIS_SERIES,
                    "Stormlight" to ParentPickerDisabledReason.INSIDE_THIS_SERIES,
                    "Mistborn" to ParentPickerDisabledReason.INSIDE_THIS_SERIES,
                    "Narnia" to null,
                )
        }

        test("the parent a series already has is marked current; its siblings stay choosable") {
            rows("Mistborn").map { it.name to it.disabledReason } shouldContainExactly
                listOf(
                    "Cosmere" to ParentPickerDisabledReason.CURRENT_PARENT,
                    "Stormlight" to null,
                    "Mistborn" to ParentPickerDisabledReason.THIS_SERIES,
                    "Era 1" to ParentPickerDisabledReason.INSIDE_THIS_SERIES,
                    "Narnia" to null,
                )
        }

        test("a collapsed node hides its sub-series; rows carry depth and counts") {
            val collapsed = rows("Narnia", expanded = emptySet())
            collapsed.map { it.name } shouldContainExactly listOf("Cosmere", "Narnia")
            collapsed.first().subSeriesCount shouldBe 2
            collapsed.first().bookCount shouldBe 2
            collapsed.first().isExpanded shouldBe false

            rows("Narnia", expanded = setOf("Cosmere")).map { it.name to it.depth } shouldContainExactly
                listOf("Cosmere" to 0, "Stormlight" to 1, "Mistborn" to 1, "Narnia" to 0)
        }

        test("the query lists every match flat, with where it sits, ignoring case") {
            rows("Narnia", query = "ERA").map { it.name to it.pathNames } shouldContainExactly
                listOf("Era 1" to listOf("Cosmere", "Mistborn"))
        }
    })
