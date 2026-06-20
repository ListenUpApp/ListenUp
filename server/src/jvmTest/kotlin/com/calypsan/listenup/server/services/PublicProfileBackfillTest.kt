package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class PublicProfileBackfillTest :
    FunSpec({
        test("backfillAll creates a projection row per active user") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    UserEntity.new("u1") {
                        email = "ada@example.com"
                        emailNormalized = "ada@example.com"
                        passwordHash = "phc"
                        role = UserRoleColumn.MEMBER
                        displayName = "Ada"
                        avatarType = "auto"
                        status = UserStatusColumn.ACTIVE
                        createdAt = 1L
                        updatedAt = 1L
                    }
                    UserEntity.new("u2") {
                        email = "babbage@example.com"
                        emailNormalized = "babbage@example.com"
                        passwordHash = "phc"
                        role = UserRoleColumn.MEMBER
                        displayName = "Babbage"
                        avatarType = "auto"
                        status = UserStatusColumn.ACTIVE
                        createdAt = 1L
                        updatedAt = 1L
                    }
                }

                val repo = PublicProfileRepository(db = db.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
                val maintainer = PublicProfileMaintainer(sql = db.asSqlDatabase(), db = db, publicProfileRepo = repo)

                runTest {
                    maintainer.backfillAll()

                    val ids = repo.pullSince(userId = null, cursor = 0, limit = 50).items.map { it.id }
                    ids shouldContainExactlyInAnyOrder listOf("u1", "u2")
                }
            }
        }
    })
