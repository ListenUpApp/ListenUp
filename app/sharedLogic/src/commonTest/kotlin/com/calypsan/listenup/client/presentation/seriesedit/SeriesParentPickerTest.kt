package com.calypsan.listenup.client.presentation.seriesedit

import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly

class SeriesParentPickerTest :
    FunSpec({
        fun entity(
            id: String,
            parent: String? = null,
            position: Int? = null,
            deletedAt: Long? = null,
        ) = SeriesEntity(
            id = SeriesId(id),
            name = id,
            description = null,
            parentId = parent,
            parentPosition = position,
            deletedAt = deletedAt,
            createdAt = Timestamp(0),
            updatedAt = Timestamp(0),
        )

        val all =
            listOf(
                entity("Cosmere"),
                entity("Mistborn", parent = "Cosmere", position = 1),
                entity("Stormlight", parent = "Cosmere", position = 0),
                entity("Era 1", parent = "Mistborn", position = 0),
                entity("Narnia"),
                entity("Deleted Saga", deletedAt = 123L),
            )

        test("a series can't be placed under itself or its own sub-series") {
            parentCandidates(all, currentId = "Mistborn", query = "").map { it.displayName } shouldContainExactly
                listOf("Cosmere", "Narnia", "Stormlight")
        }

        test("the query narrows candidates, ignoring case") {
            parentCandidates(all, currentId = "Era 1", query = "cos").map { it.displayName } shouldContainExactly
                listOf("Cosmere")
        }
    })
