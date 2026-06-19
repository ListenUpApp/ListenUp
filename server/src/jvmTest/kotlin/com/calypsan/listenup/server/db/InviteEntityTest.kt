package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class InviteEntityTest :
    FunSpec({
        test("InviteEntity persists + maps to InviteDto") {
            withInMemoryDatabase {
                val dto =
                    transaction(this) {
                        InviteEntity
                            .new("i1") {
                                code = "c1"
                                email = "a@b.c"
                                displayName = "A"
                                role = UserRoleColumn.MEMBER
                                createdBy = "admin1"
                                expiresAt = 100L
                                createdAt = 0L
                            }.toDto()
                    }
                dto.id.value shouldBe "i1"
                dto.code shouldBe "c1"
                dto.role.name shouldBe "MEMBER"
                dto.claimedAt shouldBe null
            }
        }
    })
