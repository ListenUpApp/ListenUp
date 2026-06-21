package com.calypsan.listenup.server.db

import app.cash.sqldelight.db.QueryResult
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class CollectionsMigrationTest :
    FunSpec({
        test("V24 creates collections, collection_books, collection_shares") {
            withSqlDatabase {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        // Verify each table exists by querying its row count (0 is fine).
                        for (table in listOf("collections", "collection_books", "collection_grants")) {
                            val count =
                                driver
                                    .executeQuery(
                                        identifier = null,
                                        sql = "SELECT COUNT(*) FROM $table",
                                        mapper = { cursor ->
                                            cursor.next()
                                            QueryResult.Value(cursor.getLong(0))
                                        },
                                        parameters = 0,
                                        binders = null,
                                    ).value
                            count shouldBe 0L
                        }
                    }
                }
            }
        }
    })
