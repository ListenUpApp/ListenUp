package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class LibraryTableTest :
    FunSpec({
        test("LibraryTable has the expected columns for the Libraries phase schema") {
            val columnNames = LibraryTable.columns.map { it.name }
            // Core identity + display columns
            columnNames shouldContainAll
                listOf(
                    "id",
                    "name",
                    "metadata_precedence",
                    "access_mode",
                    "created_by_user_id",
                )
            // Sync substrate columns inherited from SyncableTable
            columnNames shouldContainAll listOf("revision", "created_at", "updated_at", "deleted_at", "client_op_id")
            // V3 root_path is gone in V20
            columnNames shouldNotContain "root_path"
        }

        test("LibraryTable tableName is 'libraries'") {
            LibraryTable.tableName shouldBe "libraries"
        }

        test("LibraryTable primaryKey is id") {
            LibraryTable.primaryKey?.columns?.map { it.name } shouldBe listOf("id")
        }
    })
