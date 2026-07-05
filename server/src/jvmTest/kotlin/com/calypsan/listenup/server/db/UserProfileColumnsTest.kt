package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UserProfileColumnsTest :
    FunSpec({
        test("users row round-trips tagline + avatarType with defaults") {
            withSqlDatabase {
                sql.transaction {
                    sql.usersQueries.insert(
                        id = "u-prof-1",
                        email = "p@x",
                        email_normalized = "p@x",
                        password_hash = "h",
                        role = UserRoleColumn.MEMBER.name,
                        display_name = "Pat",
                        status = UserStatusColumn.ACTIVE.name,
                        created_at = 0L,
                        updated_at = 0L,
                        last_login_at = null,
                        can_edit = 1L,
                        can_share = 1L,
                        approved_by = null,
                        approved_at = null,
                        deleted_at = null,
                        invited_by = null,
                        tagline = null,
                        avatar_type = "auto",
                        timezone = "UTC",
                    )
                }
                val inserted = sql.usersQueries.selectById("u-prof-1").executeAsOne()
                inserted.avatar_type shouldBe "auto"
                inserted.tagline shouldBe null

                sql.transaction {
                    sql.usersQueries.updateProfileFields(
                        display_name = "Pat",
                        tagline = "Reader of books",
                        avatar_type = "image",
                        avatar_updated_at = 1L,
                        password_hash = "h",
                        updated_at = 1L,
                        id = "u-prof-1",
                    )
                }
                val updated = sql.usersQueries.selectById("u-prof-1").executeAsOne()
                updated.tagline shouldBe "Reader of books"
                updated.avatar_type shouldBe "image"
            }
        }
    })
