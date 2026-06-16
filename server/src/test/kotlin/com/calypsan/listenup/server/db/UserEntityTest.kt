package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files

class UserEntityTest :
    FunSpec({

        test("UserEntity round-trips through the users table") {
            val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
            val db = DatabaseFactory.init(DatabaseConfig("jdbc:sqlite:${tmp.absolutePath}")).database

            val id = "u-1"
            transaction(db) {
                UserEntity.new(id) {
                    email = "Alice@Example.com"
                    emailNormalized = "alice@example.com"
                    passwordHash = "phc-string"
                    role = UserRoleColumn.MEMBER
                    displayName = "Alice"
                    status = UserStatusColumn.ACTIVE
                    createdAt = 1L
                    updatedAt = 1L
                }
            }

            transaction(db) {
                val u = UserEntity.findById(id)!!
                u.email shouldBe "Alice@Example.com"
                u.emailNormalized shouldBe "alice@example.com"
                u.role shouldBe UserRoleColumn.MEMBER
                u.status shouldBe UserStatusColumn.ACTIVE
            }
        }

        test("timezone defaults to UTC for new users") {
            val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
            val db = DatabaseFactory.init(DatabaseConfig("jdbc:sqlite:${tmp.absolutePath}")).database

            transaction(db) {
                UserEntity.new("u-tz-default") {
                    email = "bob@example.com"
                    emailNormalized = "bob@example.com"
                    passwordHash = "phc-string"
                    role = UserRoleColumn.MEMBER
                    displayName = "Bob"
                    status = UserStatusColumn.ACTIVE
                    createdAt = 1L
                    updatedAt = 1L
                }
            }

            transaction(db) {
                UserEntity["u-tz-default"].timezone shouldBe "UTC"
            }
        }

        test("timezone persists when updated") {
            val tmp = Files.createTempFile("listenup-test-", ".db").toFile().apply { deleteOnExit() }
            val db = DatabaseFactory.init(DatabaseConfig("jdbc:sqlite:${tmp.absolutePath}")).database

            transaction(db) {
                UserEntity.new("u-tz-update") {
                    email = "carol@example.com"
                    emailNormalized = "carol@example.com"
                    passwordHash = "phc-string"
                    role = UserRoleColumn.MEMBER
                    displayName = "Carol"
                    status = UserStatusColumn.ACTIVE
                    createdAt = 1L
                    updatedAt = 1L
                }
            }

            transaction(db) {
                UserEntity["u-tz-update"].timezone = "Europe/London"
            }

            transaction(db) {
                UserEntity["u-tz-update"].timezone shouldBe "Europe/London"
            }
        }
    })
