package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class LibraryTableTest :
    FunSpec({
        test("LibraryTable exists with id, name, root_path columns") {
            LibraryTable.columns.map { it.name } shouldContainExactlyInAnyOrder
                listOf("id", "name", "root_path")
        }

        test("LibraryTable tableName is 'libraries'") {
            LibraryTable.tableName shouldBe "libraries"
        }

        test("LibraryTable primaryKey is id") {
            LibraryTable.primaryKey?.columns?.map { it.name } shouldBe listOf("id")
        }
    })
