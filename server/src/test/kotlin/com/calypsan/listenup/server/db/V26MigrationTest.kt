package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class V26MigrationTest :
    FunSpec({
        test("users has can_edit/can_share defaulting to 1 and approval/soft-delete columns") {
            withInMemoryDatabase {
                transaction(this) {
                    exec(
                        "INSERT INTO users " +
                            "(id, email, email_normalized, password_hash, role, display_name, status, created_at, updated_at) " +
                            "VALUES ('u1','a@b.c','a@b.c','h','MEMBER','A','ACTIVE',0,0)",
                    )
                    exec("SELECT can_edit, can_share, approved_by, approved_at, deleted_at FROM users WHERE id='u1'") { rs ->
                        rs.next()
                        rs.getInt("can_edit") shouldBe 1
                        rs.getInt("can_share") shouldBe 1
                        rs.getObject("deleted_at") shouldBe null
                    }
                }
            }
        }
        test("server_settings starts empty so the startup default governs until an admin sets a policy") {
            withInMemoryDatabase {
                transaction(this) {
                    exec("SELECT COUNT(*) FROM server_settings") { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 0
                    }
                }
            }
        }
    })
