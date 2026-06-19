package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class V27MigrationTest :
    FunSpec({
        test("invites table + users.invited_by exist") {
            withInMemoryDatabase {
                transaction(this) {
                    exec(
                        "INSERT INTO invites " +
                            "(id, code, email, display_name, role, created_by, expires_at, created_at) " +
                            "VALUES ('i1','c1','a@b.c','A','MEMBER','admin1',100,0)",
                    )
                    exec("SELECT claimed_at, claimed_by FROM invites WHERE id='i1'") { rs ->
                        rs.next()
                        rs.getObject("claimed_at") shouldBe null
                    }
                    exec(
                        "INSERT INTO users " +
                            "(id, email, email_normalized, password_hash, role, display_name, status, created_at, updated_at) " +
                            "VALUES ('u1','x@y.z','x@y.z','h','MEMBER','U','ACTIVE',0,0)",
                    )
                    exec("SELECT invited_by FROM users WHERE id='u1'") { rs ->
                        rs.next()
                        rs.getObject("invited_by") shouldBe null
                    }
                }
            }
        }
        test("invites.code is unique") {
            withInMemoryDatabase {
                transaction(this) {
                    exec(
                        "INSERT INTO invites " +
                            "(id, code, email, display_name, role, created_by, expires_at, created_at) " +
                            "VALUES ('i1','dupe','a@b.c','A','MEMBER','adm',100,0)",
                    )
                    runCatching {
                        exec(
                            "INSERT INTO invites " +
                                "(id, code, email, display_name, role, created_by, expires_at, created_at) " +
                                "VALUES ('i2','dupe','b@c.d','B','MEMBER','adm',100,0)",
                        )
                    }.isFailure shouldBe true
                }
            }
        }
    })
