package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.SeriesError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SeriesHierarchyWireTest :
    FunSpec({
        val json = contractJson

        test("a payload from a server without hierarchy decodes as a root") {
            val legacy =
                """{"id":"s1","name":"Mistborn","sortName":null,"revision":3,"updatedAt":2,"createdAt":1,"deletedAt":null}"""
            val payload = json.decodeFromString(SeriesSyncPayload.serializer(), legacy)
            payload.parentId shouldBe null
            payload.parentPosition shouldBe null
        }

        test("parent and position survive a round trip") {
            val payload =
                SeriesSyncPayload(
                    id = "s1",
                    name = "Mistborn",
                    sortName = null,
                    revision = 3,
                    updatedAt = 2,
                    createdAt = 1,
                    deletedAt = null,
                    parentId = "cosmere",
                    parentPosition = 4,
                )
            val decoded =
                json.decodeFromString(
                    SeriesSyncPayload.serializer(),
                    json.encodeToString(SeriesSyncPayload.serializer(), payload),
                )
            decoded shouldBe payload
        }

        test("the hierarchy errors cross the wire as their own types") {
            val errors: List<AppError> =
                listOf(SeriesError.ParentNotFound(), SeriesError.HierarchyCycle(), SeriesError.NameAlreadyExists())
            errors.forEach { error ->
                json.decodeFromString(AppError.serializer(), json.encodeToString(AppError.serializer(), error)) shouldBe
                    error
                error.isRetryable shouldBe false
            }
        }
    })
