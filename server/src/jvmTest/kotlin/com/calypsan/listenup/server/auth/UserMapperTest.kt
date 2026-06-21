package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UserMapperTest :
    FunSpec({
        test("toContract carries permissions + approval audit") {
            withSqlDatabase {
                sql.transaction {
                    sql.usersQueries.insert(
                        id = "u1",
                        email = "a@b.c",
                        email_normalized = "a@b.c",
                        password_hash = "h",
                        role = UserRoleColumn.MEMBER.name,
                        display_name = "A",
                        status = UserStatusColumn.ACTIVE.name,
                        created_at = 0L,
                        updated_at = 0L,
                        last_login_at = null,
                        can_edit = 0L,
                        can_share = 1L,
                        approved_by = "admin1",
                        approved_at = 123L,
                        deleted_at = null,
                        invited_by = null,
                        tagline = null,
                        avatar_type = "auto",
                        timezone = "UTC",
                    )
                }
                val user =
                    sql.usersQueries
                        .selectById("u1")
                        .executeAsOne()
                        .toAuthUser()
                        .toContract()
                user.permissions shouldBe UserPermissions(canEdit = false, canShare = true)
                user.approvedBy shouldBe "admin1"
                user.approvedAt shouldBe 123L
            }
        }
    })
