package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class UserMapperTest :
    FunSpec({
        test("toContract carries permissions + approval audit") {
            withInMemoryDatabase {
                val user =
                    transaction(this) {
                        UserEntity
                            .new("u1") {
                                email = "a@b.c"
                                emailNormalized = "a@b.c"
                                passwordHash = "h"
                                role = UserRoleColumn.MEMBER
                                displayName = "A"
                                status = UserStatusColumn.ACTIVE
                                createdAt = 0
                                updatedAt = 0
                                canEdit = false
                                canShare = true
                                approvedBy = "admin1"
                                approvedAt = 123L
                            }.toContract()
                    }
                user.permissions shouldBe UserPermissions(canEdit = false, canShare = true)
                user.approvedBy shouldBe "admin1"
                user.approvedAt shouldBe 123L
            }
        }
    })
