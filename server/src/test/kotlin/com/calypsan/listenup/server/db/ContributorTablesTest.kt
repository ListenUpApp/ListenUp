package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class ContributorTablesTest :
    FunSpec({

        test("ContributorTable has id, normalized_name, name, sort_name + sync columns + B2a enrichment columns") {
            ContributorTable.columns.map { it.name } shouldContainExactlyInAnyOrder
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
                    // B2a enrichment
                    "asin",
                    "description",
                    "image_path",
                    "image_blur_hash",
                    "birth_date",
                    "death_date",
                    "website",
                )
        }

        test("ContributorTable tableName is 'contributors'") {
            ContributorTable.tableName shouldBe "contributors"
        }

        test("ContributorTable primary key is id") {
            ContributorTable.primaryKey?.columns?.map { it.name } shouldBe listOf("id")
        }

        test("BookContributorTable has book_id, contributor_id, role, credited_as, ordinal") {
            BookContributorTable.columns.map { it.name } shouldContainExactlyInAnyOrder
                listOf("book_id", "contributor_id", "role", "credited_as", "ordinal")
        }

        test("BookContributorTable composite PK includes role for multi-role same contributor") {
            BookContributorTable.primaryKey?.columns?.map { it.name } shouldBe
                listOf("book_id", "contributor_id", "role")
        }

        test("BookContributorTable tableName is 'book_contributors'") {
            BookContributorTable.tableName shouldBe "book_contributors"
        }
    })
