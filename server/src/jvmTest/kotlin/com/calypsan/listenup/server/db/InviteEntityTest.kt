package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InviteEntityTest :
    FunSpec({
        test("InviteEntity persists + maps to InviteDto") {
            withSqlDatabase {
                sql.transaction {
                    sql.invitesQueries.insert(
                        id = "i1",
                        code = "c1",
                        email = "a@b.c",
                        display_name = "A",
                        role = UserRoleColumn.MEMBER.name,
                        created_by = "admin1",
                        expires_at = 100L,
                        created_at = 0L,
                    )
                }
                val row = sql.invitesQueries.selectById("i1").executeAsOne()
                row.id shouldBe "i1"
                row.code shouldBe "c1"
                row.role shouldBe UserRoleColumn.MEMBER.name
                row.claimed_at shouldBe null
            }
        }
    })
