package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files

class SessionEntityTest :
    FunSpec({

        test("SessionEntity round-trips and FK to UserEntity") {
            val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
            val db = DatabaseFactory.init(DatabaseConfig("jdbc:sqlite:${tmp.absolutePath}"))

            transaction(db) {
                UserEntity.new("u-1") {
                    email = "a@b"
                    emailNormalized = "a@b"
                    passwordHash = "phc"
                    role = UserRoleColumn.MEMBER
                    displayName = "A"
                    status = UserStatusColumn.ACTIVE
                    createdAt = 1L
                    updatedAt = 1L
                }
                SessionEntity.new("s-1") {
                    user = UserEntity["u-1"]
                    refreshTokenHash = "rth"
                    familyId = "f-1"
                    previousHash = null
                    label = "iPhone"
                    userAgent = null
                    createdAt = 1L
                    expiresAt = 100L
                    lastUsedAt = 1L
                    revokedAt = null
                }
            }

            transaction(db) {
                val s = SessionEntity["s-1"]
                s.user.id.value shouldBe "u-1"
                s.refreshTokenHash shouldBe "rth"
                s.familyId shouldBe "f-1"
                s.revokedAt shouldBe null
                s.previousHash shouldBe null
                s.userAgent shouldBe null
            }
        }

        test("deleting a user cascades to delete their sessions") {
            val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
            val db = DatabaseFactory.init(DatabaseConfig("jdbc:sqlite:${tmp.absolutePath}"))

            transaction(db) {
                UserEntity.new("u-1") {
                    email = "a@b"
                    emailNormalized = "a@b"
                    passwordHash = "phc"
                    role = UserRoleColumn.MEMBER
                    displayName = "A"
                    status = UserStatusColumn.ACTIVE
                    createdAt = 1L
                    updatedAt = 1L
                }
                SessionEntity.new("s-1") {
                    user = UserEntity["u-1"]
                    refreshTokenHash = "rth"
                    familyId = "f-1"
                    previousHash = null
                    label = null
                    userAgent = null
                    createdAt = 1L
                    expiresAt = 100L
                    lastUsedAt = 1L
                    revokedAt = null
                }
            }

            transaction(db) { UserEntity["u-1"].delete() }

            transaction(db) {
                SessionEntity.findById("s-1") shouldBe null
            }
        }
    })
