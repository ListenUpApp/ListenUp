package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class CollectionsMigrationTest :
    FunSpec({
        test("V24 creates collections, collection_books, collection_shares") {
            withInMemoryDatabase {
                transaction(this) {
                    CollectionsTable.selectAll().toList()
                    CollectionBooksTable.selectAll().toList()
                    CollectionSharesTable.selectAll().toList()
                }
            }
        }
    })
