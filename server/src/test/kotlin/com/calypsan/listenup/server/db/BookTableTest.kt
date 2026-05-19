package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class BookTableTest :
    FunSpec({
        test("BookTable extends SyncableTable") {
            // Compile-time check: if BookTable doesn't extend SyncableTable, this won't compile.
            val table: SyncableTable = BookTable
            check(table.tableName == "books")
        }

        test("BookTable tableName is 'books'") {
            BookTable.tableName shouldBe "books"
        }

        test("BookTable has the spec'd column set") {
            BookTable.columns.map { it.name } shouldContainExactlyInAnyOrder
                listOf(
                    "id",
                    "library_id",
                    "title",
                    "sort_title",
                    "subtitle",
                    "description",
                    "publish_year",
                    "publisher",
                    "language",
                    "isbn",
                    "asin",
                    "abridged",
                    "explicit",
                    "has_scan_warning",
                    "total_duration",
                    "cover_source",
                    "cover_path",
                    "cover_hash",
                    "root_rel_path",
                    "inode",
                    "scanned_at",
                    "revision",
                    "created_at",
                    "updated_at",
                    "deleted_at",
                    "client_op_id",
                )
        }

        test("BookTable primaryKey is id") {
            BookTable.primaryKey?.columns?.map { it.name } shouldBe listOf("id")
        }
    })
