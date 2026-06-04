package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.UserEntity
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatsTable
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class PublicProfileMaintainerTest :
    FunSpec({
        test("refresh builds a row from users + user_stats") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    UserEntity.new("u1") {
                        email = "ada@example.com"
                        emailNormalized = "ada@example.com"
                        passwordHash = "phc"
                        role = UserRoleColumn.MEMBER
                        displayName = "Ada"
                        avatarType = "image"
                        status = UserStatusColumn.ACTIVE
                        createdAt = 1L
                        updatedAt = 1L
                    }
                    UserStatsTable.insert { stmt ->
                        stmt[UserStatsTable.id] = "u1"
                        stmt[UserStatsTable.userId] = "u1"
                        stmt[UserStatsTable.totalSecondsAllTime] = 5000L
                        stmt[UserStatsTable.totalSecondsLast7Days] = 700L
                        stmt[UserStatsTable.totalSecondsLast30Days] = 3000L
                        stmt[UserStatsTable.booksFinished] = 4
                        stmt[UserStatsTable.currentStreakDays] = 3
                        stmt[UserStatsTable.longestStreakDays] = 9
                        stmt[UserStatsTable.revision] = 1L
                        stmt[UserStatsTable.createdAt] = 1L
                        stmt[UserStatsTable.updatedAt] = 1L
                    }
                }

                val repo = PublicProfileRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                val maintainer = PublicProfileMaintainer(db = db, publicProfileRepo = repo)

                runTest {
                    maintainer.refresh("u1")

                    val saved = repo.pullSince(userId = null, cursor = 0, limit = 10).items.single()
                    saved.id shouldBe "u1"
                    saved.displayName shouldBe "Ada"
                    saved.avatarType shouldBe "image"
                    saved.totalSecondsAllTime shouldBe 5000L
                    saved.booksFinished shouldBe 4
                    saved.longestStreakDays shouldBe 9
                    // No listening events seeded → window sum is 0
                    saved.totalSecondsLast365Days shouldBe 0L
                }
            }
        }

        test("refresh on a user with no user_stats row yields a zero-stats projection") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
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

                val repo = PublicProfileRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                val maintainer = PublicProfileMaintainer(db = db, publicProfileRepo = repo)

                runTest {
                    maintainer.refresh("u2")

                    val saved = repo.pullSince(userId = null, cursor = 0, limit = 10).items.single()
                    saved.id shouldBe "u2"
                    saved.displayName shouldBe "Babbage"
                    saved.totalSecondsAllTime shouldBe 0L
                    saved.booksFinished shouldBe 0
                }
            }
        }

        test("tombstone soft-deletes the projection row") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    UserEntity.new("u3") {
                        email = "turing@example.com"
                        emailNormalized = "turing@example.com"
                        passwordHash = "phc"
                        role = UserRoleColumn.MEMBER
                        displayName = "Turing"
                        avatarType = "auto"
                        status = UserStatusColumn.ACTIVE
                        createdAt = 1L
                        updatedAt = 1L
                    }
                }

                val repo = PublicProfileRepository(db = db, bus = ChangeBus(), registry = SyncRegistry())
                val maintainer = PublicProfileMaintainer(db = db, publicProfileRepo = repo)

                runTest {
                    maintainer.refresh("u3")
                    maintainer.tombstone("u3")

                    // pullSince returns all rows including soft-deleted ones
                    val saved = repo.pullSince(userId = null, cursor = 0, limit = 10).items.single()
                    saved.deletedAt shouldNotBe null
                    saved.deletedAt.shouldNotBeNull()
                }
            }
        }
    })
