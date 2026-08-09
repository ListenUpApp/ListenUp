package com.calypsan.listenup.server.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** One column row from `PRAGMA table_info`: declared type, notnull flag, default value. */
private data class ColumnInfo(
    val type: String,
    val notNull: Long,
    val defaultValue: String?,
)

/** Reflects [table]'s columns (name → type/notnull/default) via `PRAGMA table_info`. */
private fun SqlDriver.tableInfo(table: String): Map<String, ColumnInfo> =
    executeQuery(
        identifier = null,
        sql = "PRAGMA table_info('$table')",
        mapper = { cursor ->
            QueryResult.Value(
                buildMap {
                    while (cursor.next().value) {
                        put(
                            cursor.getString(1)!!,
                            ColumnInfo(
                                type = cursor.getString(2)!!.uppercase(),
                                notNull = cursor.getLong(3)!!,
                                defaultValue = cursor.getString(4),
                            ),
                        )
                    }
                },
            )
        },
        parameters = 0,
        binders = null,
    ).value

class V56MigrationTest :
    FunSpec({
        test("playback_positions gains volume_boost_db (REAL NOT NULL DEFAULT 0.0) and measured_gain_db (REAL NULL)") {
            withSqlDatabase {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        val columns = driver.tableInfo("playback_positions")
                        columns["volume_boost_db"] shouldBe
                            ColumnInfo(type = "REAL", notNull = 1L, defaultValue = "0.0")
                        columns["measured_gain_db"] shouldBe
                            ColumnInfo(type = "REAL", notNull = 0L, defaultValue = null)
                    }
                }
            }
        }
        test("user_settings gains default_volume_boost_db (REAL NOT NULL DEFAULT 0.0)") {
            withSqlDatabase {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        val columns = driver.tableInfo("user_settings")
                        columns["default_volume_boost_db"] shouldBe
                            ColumnInfo(type = "REAL", notNull = 1L, defaultValue = "0.0")
                    }
                }
            }
        }
        test("books gains normalization_gain_db (REAL NULL)") {
            withSqlDatabase {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        val columns = driver.tableInfo("books")
                        columns["normalization_gain_db"] shouldBe
                            ColumnInfo(type = "REAL", notNull = 0L, defaultValue = null)
                    }
                }
            }
        }
    })
