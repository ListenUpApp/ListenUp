package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class UserProfileColumnsTest :
    FunSpec({
        test("users row round-trips tagline + avatarType with defaults") {
            withInMemoryDatabase {
                transaction(this) {
                    UserEntity.new("u-prof-1") {
                        email = "p@x"
                        emailNormalized = "p@x"
                        passwordHash = "h"
                        role = UserRoleColumn.MEMBER
                        displayName = "Pat"
                        status = UserStatusColumn.ACTIVE
                        createdAt = 0
                        updatedAt = 0
                    }
                }
                transaction(this) {
                    val u = UserEntity.findById("u-prof-1")!!
                    u.avatarType shouldBe "auto"
                    u.tagline shouldBe null
                    u.tagline = "Reader of books"
                    u.avatarType = "image"
                }
                transaction(this) {
                    val u = UserEntity.findById("u-prof-1")!!
                    u.tagline shouldBe "Reader of books"
                    u.avatarType shouldBe "image"
                }
            }
        }
    })
