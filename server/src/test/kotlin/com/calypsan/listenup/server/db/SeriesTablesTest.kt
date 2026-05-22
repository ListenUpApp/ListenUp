package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class SeriesTablesTest :
    FunSpec({

        test("BookSeriesTable has id, normalized_name, name, sort_name + sync columns") {
            BookSeriesTable.columns.map { it.name } shouldContainExactlyInAnyOrder
                listOf(
                    "id",
                    "normalized_name",
                    "name",
                    "sort_name",
                    "revision",
                    "created_at",
                    "updated_at",
                    "deleted_at",
                    "client_op_id",
                )
        }

        test("BookSeriesTable tableName is 'book_series'") {
            BookSeriesTable.tableName shouldBe "book_series"
        }

        test("BookSeriesTable primary key is id") {
            BookSeriesTable.primaryKey?.columns?.map { it.name } shouldBe listOf("id")
        }

        test("BookSeriesMembershipTable has book_id, series_id, sequence, ordinal") {
            BookSeriesMembershipTable.columns.map { it.name } shouldContainExactlyInAnyOrder
                listOf("book_id", "series_id", "sequence", "ordinal")
        }

        test("BookSeriesMembershipTable composite PK is (book_id, series_id)") {
            BookSeriesMembershipTable.primaryKey?.columns?.map { it.name } shouldBe
                listOf("book_id", "series_id")
        }

        test("BookSeriesMembershipTable tableName is 'book_series_memberships'") {
            BookSeriesMembershipTable.tableName shouldBe "book_series_memberships"
        }
    })
