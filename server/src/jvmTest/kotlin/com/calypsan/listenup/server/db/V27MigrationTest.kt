package com.calypsan.listenup.server.db

import app.cash.sqldelight.db.QueryResult
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class V27MigrationTest :
    FunSpec({
        test("invites table + users.invited_by exist") {
            withSqlDatabase {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        driver.execute(
                            identifier = null,
                            sql =
                                "INSERT INTO invites " +
                                    "(id, code, email, display_name, role, created_by, expires_at, created_at) " +
                                    "VALUES ('i1','c1','a@b.c','A','MEMBER','admin1',100,0)",
                            parameters = 0,
                            binders = null,
                        )
                        val claimedAt =
                            driver
                                .executeQuery(
                                    identifier = null,
                                    sql = "SELECT claimed_at FROM invites WHERE id='i1'",
                                    mapper = { cursor ->
                                        cursor.next()
                                        QueryResult.Value(cursor.getLong(0))
                                    },
                                    parameters = 0,
                                    binders = null,
                                ).value
                        claimedAt shouldBe null
                        driver.execute(
                            identifier = null,
                            sql =
                                "INSERT INTO users " +
                                    "(id, email, email_normalized, password_hash, role, display_name, status, created_at, updated_at) " +
                                    "VALUES ('u1','x@y.z','x@y.z','h','MEMBER','U','ACTIVE',0,0)",
                            parameters = 0,
                            binders = null,
                        )
                        val invitedBy =
                            driver
                                .executeQuery(
                                    identifier = null,
                                    sql = "SELECT invited_by FROM users WHERE id='u1'",
                                    mapper = { cursor ->
                                        cursor.next()
                                        QueryResult.Value(cursor.getString(0))
                                    },
                                    parameters = 0,
                                    binders = null,
                                ).value
                        invitedBy shouldBe null
                    }
                }
            }
        }
        test("invites.code is unique") {
            withSqlDatabase {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        driver.execute(
                            identifier = null,
                            sql =
                                "INSERT INTO invites " +
                                    "(id, code, email, display_name, role, created_by, expires_at, created_at) " +
                                    "VALUES ('i1','dupe','a@b.c','A','MEMBER','adm',100,0)",
                            parameters = 0,
                            binders = null,
                        )
                        runCatching {
                            driver.execute(
                                identifier = null,
                                sql =
                                    "INSERT INTO invites " +
                                        "(id, code, email, display_name, role, created_by, expires_at, created_at) " +
                                        "VALUES ('i2','dupe','b@c.d','B','MEMBER','adm',100,0)",
                                parameters = 0,
                                binders = null,
                            )
                        }.isFailure shouldBe true
                    }
                }
            }
        }
    })
