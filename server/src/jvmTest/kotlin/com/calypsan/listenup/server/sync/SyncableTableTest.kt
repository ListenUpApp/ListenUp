package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.db.TagTable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll

class SyncableTableTest :
    FunSpec({

        test("SyncableTable mixin contributes the sync-discipline columns") {
            val columnNames = TagTable.columns.map { it.name }
            columnNames shouldContainAll
                listOf(
                    "id",
                    "name",
                    "revision",
                    "created_at",
                    "updated_at",
                    "deleted_at",
                    "client_op_id",
                )
        }

        test("TagTable extends SyncableTable") {
            // Compile-time check: if TagTable doesn't extend SyncableTable, this won't compile.
            val table: SyncableTable = TagTable
            check(table.tableName == "tags")
        }
    })
