package com.calypsan.listenup.server.db

import app.cash.sqldelight.db.QueryResult
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class V26MigrationTest :
    FunSpec({
        test("users has can_edit/can_share defaulting to 1 and approval/soft-delete columns") {
            withSqlDatabase {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        driver.execute(
                            identifier = null,
                            sql =
                                "INSERT INTO users " +
                                    "(id, email, email_normalized, password_hash, role, display_name, status, created_at, updated_at) " +
                                    "VALUES ('u1','a@b.c','a@b.c','h','MEMBER','A','ACTIVE',0,0)",
                            parameters = 0,
                            binders = null,
                        )
                        val (canEdit, canShare, deletedAt) =
                            driver
                                .executeQuery(
                                    identifier = null,
                                    sql = "SELECT can_edit, can_share, deleted_at FROM users WHERE id='u1'",
                                    mapper = { cursor ->
                                        cursor.next()
                                        QueryResult.Value(
                                            Triple(
                                                cursor.getLong(0),
                                                cursor.getLong(1),
                                                cursor.getLong(2),
                                            ),
                                        )
                                    },
                                    parameters = 0,
                                    binders = null,
                                ).value
                        canEdit shouldBe 1L
                        canShare shouldBe 1L
                        deletedAt shouldBe null
                    }
                }
            }
        }
        test("server_settings starts empty so the startup default governs until an admin sets a policy") {
            withSqlDatabase {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        val count =
                            driver
                                .executeQuery(
                                    identifier = null,
                                    sql = "SELECT COUNT(*) FROM server_settings",
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
    })
